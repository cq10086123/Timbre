import Foundation

/// 纯 Swift 实现的 raw DEFLATE 解压（RFC 1951）。
///
/// 为什么不用 Compression 框架：zip 里 method=8 的载荷是**裸 deflate**（没有 2 字节 zlib 头和
/// adler32 尾部），`compression_decode_buffer(..., COMPRESSION_ZLIB)` 对这类数据的行为取决于
/// 系统实现版本，实测不可靠，失败时只返回 0，拿不到任何原因。自己实现一份行为完全确定。
struct Inflater {
  enum InflateError: LocalizedError {
    case truncated
    case badBlock
    case badHuffman
    case badSymbol
    case badDistance
    case tooLarge

    var errorDescription: String? {
      switch self {
      case .truncated: return "deflate 数据被截断"
      case .badBlock: return "deflate 块类型非法"
      case .badHuffman: return "deflate 霍夫曼表非法"
      case .badSymbol: return "deflate 符号越界"
      case .badDistance: return "deflate 回溯距离越界"
      case .tooLarge: return "解压后体积超出上限"
      }
    }
  }

  // MARK: - 常量表

  private static let lengthBase: [Int] = [
    3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27, 31, 35, 43, 51, 59,
    67, 83, 99, 115, 131, 163, 195, 227, 258,
  ]
  private static let lengthExtra: [Int] = [
    0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3,
    4, 4, 4, 4, 5, 5, 5, 5, 0,
  ]
  private static let distBase: [Int] = [
    1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129, 193, 257, 385, 513, 769,
    1025, 1537, 2049, 3073, 4097, 6145, 8193, 12289, 16385, 24577,
  ]
  private static let distExtra: [Int] = [
    0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8,
    9, 9, 10, 10, 11, 11, 12, 12, 13, 13,
  ]
  /// 码长字母表的传输顺序
  private static let codeLengthOrder: [Int] = [
    16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15,
  ]

  private static let fixedLit: Huffman = {
    var lengths = [Int](repeating: 0, count: 288)
    for i in 0..<144 { lengths[i] = 8 }
    for i in 144..<256 { lengths[i] = 9 }
    for i in 256..<280 { lengths[i] = 7 }
    for i in 280..<288 { lengths[i] = 8 }
    return try! Inflater.build(lengths)
  }()

  private static let fixedDist: Huffman = {
    try! Inflater.build([Int](repeating: 5, count: 30))
  }()

  // MARK: - 霍夫曼表

  private struct Huffman {
    var counts = [Int](repeating: 0, count: 16)
    var symbols: [Int] = []
  }

  private static func build(_ lengths: [Int]) throws -> Huffman {
    var table = Huffman()
    for length in lengths { table.counts[length] += 1 }
    // 全 0：空表，允许（距离表在只有一个码长时会这样）
    if table.counts[0] == lengths.count { return table }

    var left = 1
    for len in 1...15 {
      left <<= 1
      left -= table.counts[len]
      if left < 0 { throw InflateError.badHuffman }
    }

    var offsets = [Int](repeating: 0, count: 16)
    for len in 1..<15 { offsets[len + 1] = offsets[len] + table.counts[len] }
    table.symbols = [Int](repeating: 0, count: lengths.count)
    for (symbol, length) in lengths.enumerated() where length != 0 {
      table.symbols[offsets[length]] = symbol
      offsets[length] += 1
    }
    return table
  }

  // MARK: - 状态

  private let input: [UInt8]
  private var pos = 0
  private var bitBuffer: UInt32 = 0
  private var bitCount = 0
  private var output: [UInt8] = []
  private let limit: Int

  init(_ input: [UInt8], expectedSize: Int) {
    self.input = input
    // 期望值来自中央目录；给点余量，同时兜住「声明值偏小」的包
    self.limit = max(expectedSize + 4096, 1 << 20)
  }

  static func decompress(_ input: [UInt8], expectedSize: Int) throws -> [UInt8] {
    var inflater = Inflater(input, expectedSize: expectedSize)
    return try inflater.run()
  }

  // MARK: - 位读取

  private mutating func bits(_ need: Int) throws -> Int {
    while bitCount < need {
      guard pos < input.count else { throw InflateError.truncated }
      bitBuffer |= UInt32(input[pos]) << UInt32(bitCount)
      pos += 1
      bitCount += 8
    }
    let mask: UInt32 = need >= 32 ? UInt32.max : ((UInt32(1) << UInt32(need)) - 1)
    let value = Int(bitBuffer & mask)
    bitBuffer >>= UInt32(need)
    bitCount -= need
    return value
  }

  private mutating func alignToByte() {
    bitBuffer = 0
    bitCount = 0
  }

  private mutating func emit(_ value: UInt8) throws {
    guard output.count < limit else { throw InflateError.tooLarge }
    output.append(value)
  }

  private mutating func decode(_ table: Huffman) throws -> Int {
    var code = 0
    var first = 0
    var index = 0
    for len in 1...15 {
      code |= try bits(1)
      let count = table.counts[len]
      if code - first < count {
        let slot = index + (code - first)
        guard slot < table.symbols.count else { throw InflateError.badHuffman }
        return table.symbols[slot]
      }
      index += count
      first = (first + count) << 1
      code <<= 1
    }
    throw InflateError.badHuffman
  }

  // MARK: - 主循环

  private mutating func run() throws -> [UInt8] {
    var isFinal = false
    repeat {
      isFinal = try bits(1) == 1
      let type = try bits(2)
      switch type {
      case 0:
        try storedBlock()
      case 1:
        try codes(Self.fixedLit, Self.fixedDist)
      case 2:
        let tables = try dynamicTables()
        try codes(tables.lit, tables.dist)
      default:
        throw InflateError.badBlock
      }
    } while !isFinal
    return output
  }

  private mutating func storedBlock() throws {
    alignToByte()
    guard pos + 4 <= input.count else { throw InflateError.truncated }
    let len = Int(input[pos]) | (Int(input[pos + 1]) << 8)
    let nlen = Int(input[pos + 2]) | (Int(input[pos + 3]) << 8)
    guard (len ^ 0xFFFF) == nlen else { throw InflateError.badBlock }
    pos += 4
    guard pos + len <= input.count else { throw InflateError.truncated }
    for i in 0..<len { try emit(input[pos + i]) }
    pos += len
  }

  private mutating func dynamicTables() throws -> (lit: Huffman, dist: Huffman) {
    let litCount = try bits(5) + 257
    let distCount = try bits(5) + 1
    let codeLengthCount = try bits(4) + 4
    guard litCount <= 286, distCount <= 30 else { throw InflateError.badHuffman }

    var codeLengths = [Int](repeating: 0, count: 19)
    for i in 0..<codeLengthCount {
      codeLengths[Self.codeLengthOrder[i]] = try bits(3)
    }
    let codeLengthTable = try Self.build(codeLengths)

    var lengths: [Int] = []
    lengths.reserveCapacity(litCount + distCount)
    while lengths.count < litCount + distCount {
      let symbol = try decode(codeLengthTable)
      switch symbol {
      case 0..<16:
        lengths.append(symbol)
      case 16:
        guard let last = lengths.last else { throw InflateError.badHuffman }
        let repeatCount = try bits(2) + 3
        lengths.append(contentsOf: [Int](repeating: last, count: repeatCount))
      case 17:
        let repeatCount = try bits(3) + 3
        lengths.append(contentsOf: [Int](repeating: 0, count: repeatCount))
      case 18:
        let repeatCount = try bits(7) + 11
        lengths.append(contentsOf: [Int](repeating: 0, count: repeatCount))
      default:
        throw InflateError.badHuffman
      }
    }
    guard lengths.count == litCount + distCount else { throw InflateError.badHuffman }
    guard lengths[256] != 0 else { throw InflateError.badHuffman }  // 必须有块结束符

    let lit = try Self.build(Array(lengths[0..<litCount]))
    let dist = try Self.build(Array(lengths[litCount..<(litCount + distCount)]))
    return (lit, dist)
  }

  private mutating func codes(_ lit: Huffman, _ dist: Huffman) throws {
    while true {
      let symbol = try decode(lit)
      if symbol < 256 {
        try emit(UInt8(symbol))
      } else if symbol == 256 {
        return
      } else {
        let lengthIndex = symbol - 257
        guard lengthIndex < Self.lengthBase.count else { throw InflateError.badSymbol }
        let length = Self.lengthBase[lengthIndex] + (try bits(Self.lengthExtra[lengthIndex]))

        let distSymbol = try decode(dist)
        guard distSymbol < Self.distBase.count else { throw InflateError.badSymbol }
        let distance = Self.distBase[distSymbol] + (try bits(Self.distExtra[distSymbol]))
        guard distance <= output.count else { throw InflateError.badDistance }

        var source = output.count - distance
        for _ in 0..<length {
          try emit(output[source])
          source += 1
        }
      }
    }
  }
}
