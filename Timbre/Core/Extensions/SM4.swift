import Foundation

/// SM4 分组密码（GB/T 32907-2016），ECB 模式 + PKCS#7 填充。
/// 移植自 Android 版 Timbre 的 Sm4.kt，保证加密结果与安卓端一致。
enum SM4 {
  private static let sbox: [UInt8] = [
    0xd6, 0x90, 0xe9, 0xfe, 0xcc, 0xe1, 0x3d, 0xb7, 0x16, 0xb6, 0x14, 0xc2, 0x28, 0xfb, 0x2c, 0x05,
    0x2b, 0x67, 0x9a, 0x76, 0x2a, 0xbe, 0x04, 0xc3, 0xaa, 0x44, 0x13, 0x26, 0x49, 0x86, 0x06, 0x99,
    0x9c, 0x42, 0x50, 0xf4, 0x91, 0xef, 0x98, 0x7a, 0x33, 0x54, 0x0b, 0x43, 0xed, 0xcf, 0xac, 0x62,
    0xe4, 0xb3, 0x1c, 0xa9, 0xc9, 0x08, 0xe8, 0x95, 0x80, 0xdf, 0x94, 0xfa, 0x75, 0x8f, 0x3f, 0xa6,
    0x47, 0x07, 0xa7, 0xfc, 0xf3, 0x73, 0x17, 0xba, 0x83, 0x59, 0x3c, 0x19, 0xe6, 0x85, 0x4f, 0xa8,
    0x68, 0x6b, 0x81, 0xb2, 0x71, 0x64, 0xda, 0x8b, 0xf8, 0xeb, 0x0f, 0x4b, 0x70, 0x56, 0x9d, 0x35,
    0x1e, 0x24, 0x0e, 0x5e, 0x63, 0x58, 0xd1, 0xa2, 0x25, 0x22, 0x7c, 0x3b, 0x01, 0x21, 0x78, 0x87,
    0xd4, 0x00, 0x46, 0x57, 0x9f, 0xd3, 0x27, 0x52, 0x4c, 0x36, 0x02, 0xe7, 0xa0, 0xc4, 0xc8, 0x9e,
    0xea, 0xbf, 0x8a, 0xd2, 0x40, 0xc7, 0x38, 0xb5, 0xa3, 0xf7, 0xf2, 0xce, 0xf9, 0x61, 0x15, 0xa1,
    0xe0, 0xae, 0x5d, 0xa4, 0x9b, 0x34, 0x1a, 0x55, 0xad, 0x93, 0x32, 0x30, 0xf5, 0x8c, 0xb1, 0xe3,
    0x1d, 0xf6, 0xe2, 0x2e, 0x82, 0x66, 0xca, 0x60, 0xc0, 0x29, 0x23, 0xab, 0x0d, 0x53, 0x4e, 0x6f,
    0xd5, 0xdb, 0x37, 0x45, 0xde, 0xfd, 0x8e, 0x2f, 0x03, 0xff, 0x6a, 0x72, 0x6d, 0x6c, 0x5b, 0x51,
    0x8d, 0x1b, 0xaf, 0x92, 0xbb, 0xdd, 0xbc, 0x7f, 0x11, 0xd9, 0x5c, 0x41, 0x1f, 0x10, 0x5a, 0xd8,
    0x0a, 0xc1, 0x31, 0x88, 0xa5, 0xcd, 0x7b, 0xbd, 0x2d, 0x74, 0xd0, 0x12, 0xb8, 0xe5, 0xb4, 0xb0,
    0x89, 0x69, 0x97, 0x4a, 0x0c, 0x96, 0x77, 0x7e, 0x65, 0xb9, 0xf1, 0x09, 0xc5, 0x6e, 0xc6, 0x84,
    0x18, 0xf0, 0x7d, 0xec, 0x3a, 0xdc, 0x4d, 0x20, 0x79, 0xee, 0x5f, 0x3e, 0xd7, 0xcb, 0x39, 0x48,
  ]

  private static let fk: [UInt32] = [0xa3b1bac6, 0x56aa3350, 0x677d9197, 0xb27022dc]

  private static let ck: [UInt32] = (0..<32).map { i -> UInt32 in
    var value: UInt32 = 0
    for j in 0..<4 {
      let byte = UInt32(((4 * i + j) * 7) % 256)
      value |= byte << UInt32(8 * (3 - j))
    }
    return value
  }

  static func encrypt(_ data: [UInt8], key: [UInt8]) throws -> [UInt8] {
    guard key.count == 16 else { throw CryptoError.sm4KeySize }
    let roundKeys = expandKey(key)
    let padded = pkcs7Pad(data)
    var out = [UInt8](repeating: 0, count: padded.count)
    var offset = 0
    while offset < padded.count {
      cryptBlock(roundKeys, padded, offset, &out, offset)
      offset += 16
    }
    return out
  }

  static func decrypt(_ data: [UInt8], key: [UInt8]) throws -> [UInt8] {
    guard key.count == 16 else { throw CryptoError.sm4KeySize }
    guard !data.isEmpty, data.count % 16 == 0 else { throw CryptoError.sm4BlockSize }
    let roundKeys = Array(expandKey(key).reversed())
    var out = [UInt8](repeating: 0, count: data.count)
    var offset = 0
    while offset < data.count {
      cryptBlock(roundKeys, data, offset, &out, offset)
      offset += 16
    }
    return try pkcs7Unpad(out)
  }

  // MARK: - 内部

  private static func rotl(_ x: UInt32, _ n: UInt32) -> UInt32 {
    (x << n) | (x >> (32 - n))
  }

  private static func tau(_ a: UInt32) -> UInt32 {
    (UInt32(sbox[Int((a >> 24) & 0xff)]) << 24)
      | (UInt32(sbox[Int((a >> 16) & 0xff)]) << 16)
      | (UInt32(sbox[Int((a >> 8) & 0xff)]) << 8)
      | UInt32(sbox[Int(a & 0xff)])
  }

  private static func tEnc(_ a: UInt32) -> UInt32 {
    let b = tau(a)
    return b ^ rotl(b, 2) ^ rotl(b, 10) ^ rotl(b, 18) ^ rotl(b, 24)
  }

  private static func tKey(_ a: UInt32) -> UInt32 {
    let b = tau(a)
    return b ^ rotl(b, 13) ^ rotl(b, 23)
  }

  private static func word(_ block: [UInt8], _ offset: Int) -> UInt32 {
    (UInt32(block[offset]) << 24)
      | (UInt32(block[offset + 1]) << 16)
      | (UInt32(block[offset + 2]) << 8)
      | UInt32(block[offset + 3])
  }

  private static func putWord(_ value: UInt32, _ block: inout [UInt8], _ offset: Int) {
    block[offset] = UInt8((value >> 24) & 0xff)
    block[offset + 1] = UInt8((value >> 16) & 0xff)
    block[offset + 2] = UInt8((value >> 8) & 0xff)
    block[offset + 3] = UInt8(value & 0xff)
  }

  private static func expandKey(_ key: [UInt8]) -> [UInt32] {
    var k = (0..<4).map { word(key, $0 * 4) ^ fk[$0] }
    var roundKeys = [UInt32](repeating: 0, count: 32)
    for i in 0..<32 {
      let next = k[0] ^ tKey(k[1] ^ k[2] ^ k[3] ^ ck[i])
      roundKeys[i] = next
      k[0] = k[1]
      k[1] = k[2]
      k[2] = k[3]
      k[3] = next
    }
    return roundKeys
  }

  private static func cryptBlock(
    _ roundKeys: [UInt32],
    _ input: [UInt8],
    _ inOffset: Int,
    _ output: inout [UInt8],
    _ outOffset: Int
  ) {
    var x0 = word(input, inOffset)
    var x1 = word(input, inOffset + 4)
    var x2 = word(input, inOffset + 8)
    var x3 = word(input, inOffset + 12)
    for i in 0..<32 {
      let next = x0 ^ tEnc(x1 ^ x2 ^ x3 ^ roundKeys[i])
      x0 = x1
      x1 = x2
      x2 = x3
      x3 = next
    }
    putWord(x3, &output, outOffset)
    putWord(x2, &output, outOffset + 4)
    putWord(x1, &output, outOffset + 8)
    putWord(x0, &output, outOffset + 12)
  }

  private static func pkcs7Pad(_ data: [UInt8]) -> [UInt8] {
    let padLen = 16 - (data.count % 16)
    return data + [UInt8](repeating: UInt8(padLen), count: padLen)
  }

  private static func pkcs7Unpad(_ data: [UInt8]) throws -> [UInt8] {
    guard let last = data.last else { throw CryptoError.sm4Padding }
    let padLen = Int(last)
    guard padLen >= 1, padLen <= 16, padLen <= data.count else { throw CryptoError.sm4Padding }
    for i in (data.count - padLen)..<data.count where data[i] != UInt8(padLen) {
      throw CryptoError.sm4Padding
    }
    return Array(data[0..<(data.count - padLen)])
  }
}
