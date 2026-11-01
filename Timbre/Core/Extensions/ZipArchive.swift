import Foundation

/// 极简 ZIP 读取器：支持 stored / deflate、ZIP64 尾表、数据描述符（用中央目录里的真实长度）。
enum ZipArchive {
  struct Entry {
    var name: String
    var method: UInt16
    var compressedSize: Int
    var uncompressedSize: Int
    var localHeaderOffset: Int
    /// bit 3：长度写在数据描述符里，本地头是 0 —— 我们只用中央目录的值，这里留作诊断
    var usesDataDescriptor: Bool
  }

  enum ZipError: LocalizedError {
    case notZip
    case tooLarge
    case tooManyEntries(Int)
    case badPath(String)
    case badEntry(String)
    case unsupportedMethod(UInt16, String)

    var errorDescription: String? {
      switch self {
      case .notZip: return "不是合法的 zip / .jdr 文件"
      case .tooLarge: return "压缩包超过 20MB 上限"
      case .tooManyEntries(let count): return "包内文件数超过 64 个上限（实际 \(count)）"
      case .badPath(let name): return "非法的包内路径：\(name)"
      case .badEntry(let name): return "无法解压包内文件 \(name)"
      case .unsupportedMethod(let method, let name):
        return "不支持的压缩方式 \(method)（文件 \(name)），请用 store 或 deflate 重新打包"
      }
    }
  }

  static let maxPackageBytes = 20 * 1024 * 1024
  static let maxEntries = 64
  /// 解压后总字节上限，防压缩炸弹
  static let maxUncompressedBytes = 16 * 1024 * 1024

  static func entries(in data: Data) throws -> [Entry] {
    guard !data.isEmpty else { throw ZipError.notZip }
    guard data.count <= maxPackageBytes else { throw ZipError.tooLarge }
    let bytes = [UInt8](data)
    guard let eocd = findEndOfCentralDirectory(bytes) else { throw ZipError.notZip }

    var total = Int(u16(bytes, eocd + 10))
    var cdOffset = Int(u32(bytes, eocd + 16))

    // ZIP64：值溢出时真正的记录在 EOCD 前 20 字节的定位器里
    if total == 0xFFFF || cdOffset == 0xFFFF_FFFF {
      let locator = eocd - 20
      if locator >= 0, u32(bytes, locator) == 0x0706_4b50 {
        let zip64Offset = Int(u64(bytes, locator + 8))
        if zip64Offset >= 0, zip64Offset + 56 <= bytes.count,
          u32(bytes, zip64Offset) == 0x0606_4b50
        {
          total = Int(u64(bytes, zip64Offset + 32))
          cdOffset = Int(u64(bytes, zip64Offset + 48))
        }
      }
    }

    guard total <= maxEntries else { throw ZipError.tooManyEntries(total) }
    guard cdOffset >= 0, cdOffset < bytes.count else { throw ZipError.notZip }

    var cursor = cdOffset
    var result: [Entry] = []
    var inflatedTotal = 0

    for _ in 0..<total {
      guard cursor + 46 <= bytes.count, u32(bytes, cursor) == 0x0201_4b50 else {
        throw ZipError.notZip
      }
      let flags = u16(bytes, cursor + 8)
      let method = u16(bytes, cursor + 10)
      var compressed = Int(u32(bytes, cursor + 20))
      var uncompressed = Int(u32(bytes, cursor + 24))
      let nameLen = Int(u16(bytes, cursor + 28))
      let extraLen = Int(u16(bytes, cursor + 30))
      let commentLen = Int(u16(bytes, cursor + 32))
      var localOffset = Int(u32(bytes, cursor + 42))
      guard cursor + 46 + nameLen <= bytes.count else { throw ZipError.notZip }
      let nameBytes = Array(bytes[(cursor + 46)..<(cursor + 46 + nameLen)])
      let name = String(decoding: nameBytes, as: UTF8.self)
      let extraStart = cursor + 46 + nameLen

      // ZIP64 扩展字段（0x0001）按需覆盖 4 字节占位值
      if uncompressed == 0xFFFF_FFFF || compressed == 0xFFFF_FFFF
        || localOffset == 0xFFFF_FFFF
      {
        var p = extraStart
        let extraEnd = min(extraStart + extraLen, bytes.count)
        while p + 4 <= extraEnd {
          let headerId = u16(bytes, p)
          let size = Int(u16(bytes, p + 2))
          if headerId == 0x0001 {
            var q = p + 4
            if uncompressed == 0xFFFF_FFFF, q + 8 <= extraEnd {
              uncompressed = Int(u64(bytes, q)); q += 8
            }
            if compressed == 0xFFFF_FFFF, q + 8 <= extraEnd {
              compressed = Int(u64(bytes, q)); q += 8
            }
            if localOffset == 0xFFFF_FFFF, q + 8 <= extraEnd {
              localOffset = Int(u64(bytes, q)); q += 8
            }
            break
          }
          p += 4 + size
        }
      }

      if !name.hasSuffix("/") {
        try validatePath(name)
        inflatedTotal += max(uncompressed, 0)
        guard inflatedTotal <= maxUncompressedBytes else { throw ZipError.tooLarge }
        result.append(
          Entry(
            name: name,
            method: method,
            compressedSize: compressed,
            uncompressedSize: uncompressed,
            localHeaderOffset: localOffset,
            usesDataDescriptor: (flags & 0x0008) != 0
          )
        )
      }
      cursor = extraStart + extraLen + commentLen
    }
    return result
  }

  static func extract(_ entry: Entry, from data: Data) throws -> Data {
    let bytes = [UInt8](data)
    let offset = entry.localHeaderOffset
    guard offset >= 0, offset + 30 <= bytes.count, u32(bytes, offset) == 0x0403_4b50 else {
      throw ZipError.badEntry(entry.name)
    }
    let nameLen = Int(u16(bytes, offset + 26))
    let extraLen = Int(u16(bytes, offset + 28))
    let start = offset + 30 + nameLen + extraLen
    // 本地头的 compressedSize 可能是 0（数据描述符模式），一律用中央目录的值
    let end = start + entry.compressedSize
    guard start >= 0, start <= end, end <= bytes.count else {
      throw ZipError.badEntry(entry.name)
    }
    let payload = Array(bytes[start..<end])

    switch entry.method {
    case 0:
      return Data(payload)
    case 8:
      do {
        let inflated = try Inflater.decompress(payload, expectedSize: entry.uncompressedSize)
        return Data(inflated)
      } catch {
        throw ZipError.badEntry(entry.name)
      }
    default:
      throw ZipError.unsupportedMethod(entry.method, entry.name)
    }
  }

  // MARK: - 私有

  private static func validatePath(_ name: String) throws {
    if name.isEmpty || name.hasPrefix("/") || name.contains("\\") || name.contains("..") {
      throw ZipError.badPath(name)
    }
  }

  private static func findEndOfCentralDirectory(_ bytes: [UInt8]) -> Int? {
    let minSize = 22
    guard bytes.count >= minSize else { return nil }
    // EOCD 注释最长 65535，加上 EOCD 自身 22 字节
    let lowerBound = max(0, bytes.count - 65_557)
    var i = bytes.count - minSize
    while i >= lowerBound {
      if bytes[i] == 0x50, bytes[i + 1] == 0x4b, bytes[i + 2] == 0x05, bytes[i + 3] == 0x06 {
        let commentLen = Int(u16(bytes, i + 20))
        if i + minSize + commentLen == bytes.count { return i }
      }
      i -= 1
    }
    // 尾部有多余字节时放宽一次（部分打包工具会追加内容）
    i = bytes.count - minSize
    while i >= lowerBound {
      if bytes[i] == 0x50, bytes[i + 1] == 0x4b, bytes[i + 2] == 0x05, bytes[i + 3] == 0x06 {
        return i
      }
      i -= 1
    }
    return nil
  }

  private static func u16(_ bytes: [UInt8], _ offset: Int) -> UInt16 {
    guard offset >= 0, offset + 1 < bytes.count else { return 0 }
    return UInt16(bytes[offset]) | (UInt16(bytes[offset + 1]) << 8)
  }

  private static func u32(_ bytes: [UInt8], _ offset: Int) -> UInt32 {
    guard offset >= 0, offset + 3 < bytes.count else { return 0 }
    return UInt32(bytes[offset])
      | (UInt32(bytes[offset + 1]) << 8)
      | (UInt32(bytes[offset + 2]) << 16)
      | (UInt32(bytes[offset + 3]) << 24)
  }

  private static func u64(_ bytes: [UInt8], _ offset: Int) -> UInt64 {
    guard offset >= 0, offset + 7 < bytes.count else { return 0 }
    var value: UInt64 = 0
    for i in (0..<8).reversed() {
      value = (value << 8) | UInt64(bytes[offset + i])
    }
    return value
  }
}
