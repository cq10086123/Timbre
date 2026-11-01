"""逐行镜像 ZipArchive.swift + JdrArchive.swift，用真实/构造样本验证读取与校验逻辑。

只验证「读包」这一段（Swift 里没有工具链可跑，这里保证算法和分支判断是对的）。
"""
import io
import json
import os
import re
import struct
import sys
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from verify_inflate import Inflater  # noqa: E402

MAX_PACKAGE = 20 * 1024 * 1024
MAX_ENTRIES = 64
MAX_UNCOMPRESSED = 16 * 1024 * 1024
MAX_SCRIPT = 5 * 1024 * 1024
MANIFEST_ID = re.compile(r'^[a-z0-9][a-z0-9._-]{1,63}$')
SOURCE_ID = re.compile(r'^[a-z0-9][a-z0-9_-]{0,63}$')
KNOWN_CAPS = {'search', 'chapters', 'audio'}


class ZipErr(Exception):
    pass


class JdrErr(Exception):
    pass


def u16(b, o):
    if o < 0 or o + 1 >= len(b):
        return 0
    return b[o] | (b[o + 1] << 8)


def u32(b, o):
    if o < 0 or o + 3 >= len(b):
        return 0
    return b[o] | (b[o + 1] << 8) | (b[o + 2] << 16) | (b[o + 3] << 24)


def u64(b, o):
    if o < 0 or o + 7 >= len(b):
        return 0
    return struct.unpack_from('<Q', b, o)[0]


def find_eocd(b):
    n = len(b)
    if n < 22:
        return None
    lower = max(0, n - 65557)
    i = n - 22
    while i >= lower:
        if b[i:i + 4] == b'PK\x05\x06':
            if i + 22 + u16(b, i + 20) == n:
                return i
        i -= 1
    i = n - 22
    while i >= lower:
        if b[i:i + 4] == b'PK\x05\x06':
            return i
        i -= 1
    return None


class Entry:
    def __init__(self, name, method, csize, usize, lo, dd):
        self.name = name
        self.method = method
        self.compressed = csize
        self.uncompressed = usize
        self.local_offset = lo
        self.data_descriptor = dd


def entries(data):
    if not data:
        raise ZipErr('notZip(empty)')
    if len(data) > MAX_PACKAGE:
        raise ZipErr('tooLarge')
    b = data
    eocd = find_eocd(b)
    if eocd is None:
        raise ZipErr('notZip(no eocd)')
    total = u16(b, eocd + 10)
    cd = u32(b, eocd + 16)
    if total == 0xFFFF or cd == 0xFFFFFFFF:
        loc = eocd - 20
        if loc >= 0 and u32(b, loc) == 0x07064b50:
            z64 = u64(b, loc + 8)
            if z64 + 56 <= len(b) and u32(b, z64) == 0x06064b50:
                total = u64(b, z64 + 32)
                cd = u64(b, z64 + 48)
    if total > MAX_ENTRIES:
        raise ZipErr(f'tooManyEntries({total})')
    if cd < 0 or cd >= len(b):
        raise ZipErr('notZip(cd)')

    cur = cd
    out = []
    inflated = 0
    for _ in range(total):
        if cur + 46 > len(b) or u32(b, cur) != 0x02014b50:
            raise ZipErr('notZip(cd sig)')
        flags = u16(b, cur + 8)
        method = u16(b, cur + 10)
        csize = u32(b, cur + 20)
        usize = u32(b, cur + 24)
        nlen = u16(b, cur + 28)
        elen = u16(b, cur + 30)
        clen = u16(b, cur + 32)
        lo = u32(b, cur + 42)
        name = b[cur + 46:cur + 46 + nlen].decode('utf-8', 'replace')
        extra_start = cur + 46 + nlen
        if usize == 0xFFFFFFFF or csize == 0xFFFFFFFF or lo == 0xFFFFFFFF:
            p = extra_start
            end = min(extra_start + elen, len(b))
            while p + 4 <= end:
                hid = u16(b, p)
                size = u16(b, p + 2)
                if hid == 0x0001:
                    q = p + 4
                    if usize == 0xFFFFFFFF and q + 8 <= end:
                        usize = u64(b, q); q += 8
                    if csize == 0xFFFFFFFF and q + 8 <= end:
                        csize = u64(b, q); q += 8
                    if lo == 0xFFFFFFFF and q + 8 <= end:
                        lo = u64(b, q); q += 8
                    break
                p += 4 + size
        if not name.endswith('/'):
            if not name or name.startswith('/') or '\\' in name or '..' in name:
                raise ZipErr(f'badPath({name})')
            inflated += max(usize, 0)
            if inflated > MAX_UNCOMPRESSED:
                raise ZipErr('tooLarge(uncompressed)')
            out.append(Entry(name, method, csize, usize, lo, (flags & 8) != 0))
        cur = extra_start + elen + clen
    return out


def extract(entry, data):
    b = data
    off = entry.local_offset
    if off < 0 or off + 30 > len(b) or u32(b, off) != 0x04034b50:
        raise ZipErr(f'badEntry({entry.name})')
    nlen = u16(b, off + 26)
    elen = u16(b, off + 28)
    start = off + 30 + nlen + elen
    end = start + entry.compressed
    if start < 0 or start > end or end > len(b):
        raise ZipErr(f'badEntry({entry.name}) range {start}..{end} of {len(b)}')
    payload = b[start:end]
    if entry.method == 0:
        return payload
    if entry.method == 8:
        try:
            return Inflater(payload, entry.uncompressed).run()
        except Exception as e:
            raise ZipErr(f'badEntry({entry.name}) inflate {e}')
    raise ZipErr(f'unsupportedMethod({entry.method},{entry.name})')


def decode_text(raw):
    try:
        return raw.decode('utf-8').lstrip('\ufeff')
    except UnicodeDecodeError:
        pass
    try:
        return raw.decode('gb18030')
    except UnicodeDecodeError:
        return None


def parse(data):
    ents = entries(data)
    man = None
    for e in ents:
        if e.name.endswith('manifest.json') and (e.name == 'manifest.json' or True):
            if e.name == 'manifest.json' or e.name.endswith('/manifest.json'):
                man = e
                break
    if man is None:
        raise JdrErr('missingManifest')
    try:
        m = json.loads(decode_text(extract(man, data)))
    except Exception as e:
        raise JdrErr(f'badManifest(json {e})')
    if not MANIFEST_ID.match(m.get('id', '')):
        raise JdrErr(f"badManifest(id 非法: {m.get('id')})")
    if not str(m.get('name', '')).strip():
        raise JdrErr('badManifest(name 空)')
    if not str(m.get('version', '')).strip():
        raise JdrErr('badManifest(version 空)')
    sources = m.get('sources') or []
    if not sources:
        raise JdrErr('badManifest(sources 空)')
    seen = set()
    for s in sources:
        sid = s.get('id', '')
        if not SOURCE_ID.match(sid):
            raise JdrErr(f'badSource(id 非法: {sid})')
        if sid in seen:
            raise JdrErr(f'badSource(id 重复: {sid})')
        seen.add(sid)
        if not str(s.get('name', '')).strip():
            raise JdrErr(f'badSource({sid} name 空)')
        if not str(s.get('script', '')).strip():
            raise JdrErr(f'badSource({sid} script 空)')
        unknown = set(s.get('capabilities') or ['search', 'chapters', 'audio']) - KNOWN_CAPS
        if unknown:
            raise JdrErr(f'badSource({sid} 未知能力 {unknown})')
    prefix = man.name[:-len('manifest.json')] if man.name.endswith('/manifest.json') else ''
    scripts = {}
    for s in sources:
        cands = [prefix + s['script'], s['script']]
        hit = None
        for c in cands:
            for e in ents:
                if e.name == c:
                    hit = e
                    break
            if hit:
                break
        if hit is None:
            raise JdrErr(f"missingScript({s['script']})")
        if hit.uncompressed > MAX_SCRIPT:
            raise JdrErr(f"badSource(脚本 {s['script']} > 5MB)")
        raw = extract(hit, data)
        text = decode_text(raw)
        if text is None:
            raise JdrErr(f"missingScript({s['script']} 编码无法识别)")
        if not text.strip():
            raise JdrErr(f"emptyScript({s['script']})")
        scripts[s['script']] = text
    return m, scripts


def report(label, data, expect_ok=True):
    try:
        m, scripts = parse(data)
        msg = f"OK  id={m['id']} name={m['name']} v={m.get('version')} 源={[s['id'] for s in m['sources']]} 脚本={ {k: len(v) for k, v in scripts.items()} }"
        ok = True
    except (ZipErr, JdrErr) as e:
        msg = f'拒绝 {e}'
        ok = False
    flag = '' if ok == expect_ok else '   <== 与预期不符!'
    print(f'{label:<34} {msg}{flag}')
    return ok == expect_ok
