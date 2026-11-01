"""逐行镜像 Inflater.swift 的逻辑，用来验证算法本身正确（对照 zlib）。"""
import zlib, random, struct

LEN_BASE = [3,4,5,6,7,8,9,10,11,13,15,17,19,23,27,31,35,43,51,59,
            67,83,99,115,131,163,195,227,258]
LEN_EXTRA = [0,0,0,0,0,0,0,0,1,1,1,1,2,2,2,2,3,3,3,3,
             4,4,4,4,5,5,5,5,0]
DIST_BASE = [1,2,3,4,5,7,9,13,17,25,33,49,65,97,129,193,257,385,513,769,
             1025,1537,2049,3073,4097,6145,8193,12289,16385,24577]
DIST_EXTRA = [0,0,0,0,1,1,2,2,3,3,4,4,5,5,6,6,7,7,8,8,
              9,9,10,10,11,11,12,12,13,13]
CL_ORDER = [16,17,18,0,8,7,9,6,10,5,11,4,12,3,13,2,14,1,15]


class Err(Exception):
    pass


def build(lengths):
    counts = [0]*16
    for l in lengths:
        counts[l] += 1
    syms = []
    if counts[0] == len(lengths):
        return counts, syms
    left = 1
    for ln in range(1, 16):
        left <<= 1
        left -= counts[ln]
        if left < 0:
            raise Err('oversubscribed')
    offsets = [0]*16
    for ln in range(1, 15):
        offsets[ln+1] = offsets[ln] + counts[ln]
    syms = [0]*len(lengths)
    for sym, l in enumerate(lengths):
        if l != 0:
            syms[offsets[l]] = sym
            offsets[l] += 1
    return counts, syms


FIXED_LIT = build([8]*144 + [9]*112 + [7]*24 + [8]*8)
FIXED_DIST = build([5]*30)


def fixed_lit():
    return FIXED_LIT


def fixed_dist():
    return FIXED_DIST


class Inflater:
    def __init__(self, data, expected):
        self.input = data
        self.pos = 0
        self.buf = 0
        self.cnt = 0
        self.out = bytearray()
        self.limit = max(expected + 4096, 1 << 20)

    def bits(self, need):
        while self.cnt < need:
            if self.pos >= len(self.input):
                raise Err('truncated')
            self.buf |= self.input[self.pos] << self.cnt
            self.pos += 1
            self.cnt += 8
        mask = (1 << need) - 1
        v = self.buf & mask
        self.buf >>= need
        self.cnt -= need
        return v

    def align(self):
        self.buf = 0
        self.cnt = 0

    def emit(self, b):
        if len(self.out) >= self.limit:
            raise Err('tooLarge')
        self.out.append(b)

    def decode(self, table):
        counts, syms = table
        code = 0; first = 0; index = 0
        for ln in range(1, 16):
            code |= self.bits(1)
            c = counts[ln]
            if code - first < c:
                slot = index + (code - first)
                if slot >= len(syms):
                    raise Err('badHuffman')
                return syms[slot]
            index += c
            first = (first + c) << 1
            code <<= 1
        raise Err('badHuffman')

    def run(self):
        final = False
        while True:
            final = self.bits(1) == 1
            t = self.bits(2)
            if t == 0:
                self.stored()
            elif t == 1:
                self.codes(FIXED_LIT, FIXED_DIST)
            elif t == 2:
                lit, dist = self.dynamic()
                self.codes(lit, dist)
            else:
                raise Err('badBlock')
            if final:
                break
        return bytes(self.out)

    def stored(self):
        self.align()
        ln = self.input[self.pos] | (self.input[self.pos+1] << 8)
        nln = self.input[self.pos+2] | (self.input[self.pos+3] << 8)
        if (ln ^ 0xFFFF) != nln:
            raise Err('badBlock')
        self.pos += 4
        for i in range(ln):
            self.emit(self.input[self.pos+i])
        self.pos += ln

    def dynamic(self):
        litn = self.bits(5) + 257
        distn = self.bits(5) + 1
        cln = self.bits(4) + 4
        if litn > 286 or distn > 30:
            raise Err('badHuffman')
        cl = [0]*19
        for i in range(cln):
            cl[CL_ORDER[i]] = self.bits(3)
        clt = build(cl)
        lengths = []
        while len(lengths) < litn + distn:
            s = self.decode(clt)
            if s < 16:
                lengths.append(s)
            elif s == 16:
                if not lengths:
                    raise Err('badHuffman')
                r = self.bits(2) + 3
                lengths.extend([lengths[-1]]*r)
            elif s == 17:
                r = self.bits(3) + 3
                lengths.extend([0]*r)
            elif s == 18:
                r = self.bits(7) + 11
                lengths.extend([0]*r)
            else:
                raise Err('badHuffman')
        if len(lengths) != litn + distn:
            raise Err('badHuffman')
        if lengths[256] == 0:
            raise Err('badHuffman')
        return build(lengths[0:litn]), build(lengths[litn:litn+distn])

    def codes(self, lit, dist):
        while True:
            s = self.decode(lit)
            if s < 256:
                self.emit(s)
            elif s == 256:
                return
            else:
                li = s - 257
                if li >= len(LEN_BASE):
                    raise Err('badSymbol')
                ln = LEN_BASE[li] + self.bits(LEN_EXTRA[li])
                ds = self.decode(dist)
                if ds >= len(DIST_BASE):
                    raise Err('badSymbol')
                d = DIST_BASE[ds] + self.bits(DIST_EXTRA[ds])
                if d > len(self.out):
                    raise Err('badDistance')
                src = len(self.out) - d
                for _ in range(ln):
                    self.emit(self.out[src])
                    src += 1


def raw_deflate(data, level=6):
    c = zlib.compressobj(level, zlib.DEFLATED, -15)
    return c.compress(data) + c.flush()


def check(data, label):
    payload = raw_deflate(data)
    got = Inflater(payload, len(data)).run()
    ok = got == data
    print(f'  {label}: in={len(data)}B deflate={len(payload)}B -> out={len(got)}B  {"OK" if ok else "MISMATCH"}')
    return ok


if __name__ == '__main__':
    cases = []
    cases.append((b'', 'empty'))
    cases.append((b'a', 'one byte'))
    cases.append((b'hello world ' * 100, 'repeat'))
    cases.append((bytes(range(256)) * 40, 'all bytes'))
    cases.append((b'x' * 200000, 'huge repeat'))
    random.seed(7)
    cases.append((bytes(random.getrandbits(8) for _ in range(60000)), 'random'))
    cases.append((('中文测试，' * 5000).encode(), 'utf8 chinese'))
    # 真实样本里的三个脚本
    for name in ['a.js', 'l.js', 'k.js']:
        pass

    allok = True
    for data, label in cases:
        for level in (0, 1, 6, 9):
            payload = raw_deflate(data, level)
            try:
                got = Inflater(payload, len(data)).run()
                ok = got == data
            except Exception as e:
                ok = False
                got = b''
                print(f'  {label} lvl{level}: ERROR {e}')
            if not ok:
                print(f'  {label} lvl{level}: MISMATCH len={len(got)} vs {len(data)}')
            allok = allok and ok
    print('ALL OK' if allok else 'FAILURES')
