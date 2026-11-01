import Compression
import Foundation

/// 极简 ZIP 读取器（只支持 .jdr 需要的 stored / deflate，无 zip64）
enum ZipArchive {
  struct Entry {
    var name: String
    var method: UInt16
    var compressedSize: Int
    var uncompressedSize: Int
    var localHeaderOffset: Int
  }

  enum ZipError: LocalizedError {
    case notZip
    case badEntry(String)
    case tooLarge

    var errorDescription: String? {
      switch self {
      case .notZip: return "不是合法的 zip / .jdr 文件"
      case .badEntry(let name): return "无法解压包内文件 \(name)"
      case .tooLarge: return "压缩包过大"
      }
    }
  }

  static let maxPackageBytes = 32 * 1024 * 1024

  static func entries(in data: Data) throws -> [Entry] {
    guard data.count <= maxPackageBytes else { throw ZipError.tooLarge }
    let bytes = [UInt8](data)
    guard let eocd = findEndOfCentralDirectory(bytes) else { throw ZipError.notZip }

    let total = Int(u16(bytes, eocd + 10))
    let cdOffset = Int(u32(bytes, eocd + 16))
    var cursor = cdOffset
    var result: [Entry] = []

    for _ in 0..<total {
      guard cursor + 46 <= bytes.count, u32(bytes, cursor) == 0x02014b50 else {
        throw ZipError.notZip
      }
      let method = u16(bytes, cursor + 10)
      let compressed = Int(u32(bytes, cursor + 20))
      let uncompressed = Int(u32(bytes, cursor + 24))
      let nameLen = Int(u16(bytes, cursor + 28))
      let extraLen = Int(u16(bytes, cursor + 30))
      let commentLen = Int(u16(bytes, cursor + 32))
      let localOffset = Int(u32(bytes, cursor + 42))
      guard cursor + 46 + nameLen <= bytes.count else { throw ZipError.notZip }
      let nameBytes = Array(bytes[(cursor + 46)..<(cursor + 46 + nameLen)])
      let name = String(decoding: nameBytes, as: UTF8.self)
      if !name.hasSuffix("/") {
        result.append(
          Entry(
            name: name,
            method: method,
            compressedSize: compressed,
            uncompressedSize: uncompressed,
            localHeaderOffset: localOffset
          )
        )
      }
      cursor += 46 + nameLen + extraLen + commentLen
    }
    return result
  }

  static func extract(_ entry: Entry, from data: Data) throws -> Data {
    let bytes = [UInt8](data)
    let offset = entry.localHeaderOffset
    guard offset + 30 <= bytes.count, u32(bytes, offset) == 0x04034b50 else {
      throw ZipError.badEntry(entry.name)
    }
    let nameLen = Int(u16(bytes, offset + 26))
    let extraLen = Int(u16(bytes, offset + 28))
    let start = offset + 30 + nameLen + extraLen
    let end = start + entry.compressedSize
    guard start >= 0, end <= bytes.count, start <= end else {
      throw ZipError.badEntry(entry.name)
    }
    let payload = Array(bytes[start..<end])
    switch entry.method {
    case 0:
      return Data(payload)
    case 8:
      guard let inflated = inflate(payload, expected: entry.uncompressedSize) else {
        throw ZipError.badEntry(entry.name)
      }
      return Data(inflated)
    default:
      throw ZipError.badEntry(entry.name)
    }
  }

  // MARK: - 私有

  private static func findEndOfCentralDirectory(_ bytes: [UInt8]) -> Int? {
    let minSize = 22
    guard bytes.count >= minSize else { return nil }
    let lowerBound = max(0, bytes.count - 66_000)
    var i = bytes.count - minSize
    while i >= lowerBound {
      if bytes[i] == 0x50, bytes[i + 1] == 0x4b, bytes[i + 2] == 0x05, bytes[i + 3] == 0x06 {
        return i
      }
      i -= 1
    }
    return nil
  }

  private static func inflate(_ input: [UInt8], expected: Int) -> [UInt8]? {
    let capacity = max(expected, 1024)
    var output = [UInt8](repeating: 0, count: capacity)
    let written = compression_decode_buffer(
      &output,
      capacity,
      input,
      input.count,
      nil,
      COMPRESSION_ZLIB
    )
    guard written > 0 else { return nil }
    return Array(output[0..<written])
  }

  private static func u16(_ bytes: [UInt8], _ offset: Int) -> UInt16 {
    guard offset + 1 < bytes.count else { return 0 }
    return UInt16(bytes[offset]) | (UInt16(bytes[offset + 1]) << 8)
  }

  private static func u32(_ bytes: [UInt8], _ offset: Int) -> UInt32 {
    guard offset + 3 < bytes.count else { return 0 }
    return UInt32(bytes[offset])
      | (UInt32(bytes[offset + 1]) << 8)
      | (UInt32(bytes[offset + 2]) << 16)
      | (UInt32(bytes[offset + 3]) << 24)
  }
}
