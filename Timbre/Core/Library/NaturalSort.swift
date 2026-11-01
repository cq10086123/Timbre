import Foundation

/// 自然排序：让「第 2 集」排在「第 10 集」前面，并支持中文数字
enum NaturalSort {
  private static let chineseDigits: [Character: Int] = [
    "零": 0, "〇": 0, "一": 1, "壹": 1, "二": 2, "两": 2, "贰": 2, "三": 3, "叁": 3,
    "四": 4, "肆": 4, "五": 5, "伍": 5, "六": 6, "陆": 6, "七": 7, "柒": 7,
    "八": 8, "捌": 8, "九": 9, "玖": 9,
  ]

  private static let chineseUnits: [Character: Int] = [
    "十": 10, "拾": 10, "百": 100, "佰": 100, "千": 1000, "仟": 1000,
  ]

  static func compare(_ lhs: String, _ rhs: String) -> ComparisonResult {
    let left = normalize(lhs)
    let right = normalize(rhs)
    if left == right {
      return lhs.localizedStandardCompare(rhs)
    }
    return left.localizedStandardCompare(right)
  }

  /// 把中文数字规范化成阿拉伯数字，专供排序 / 展示用的轻量处理
  static func normalize(_ text: String) -> String {
    var result = ""
    var buffer = ""
    for character in text {
      if chineseDigits[character] != nil || chineseUnits[character] != nil {
        buffer.append(character)
      } else {
        if !buffer.isEmpty {
          result += String(chineseRunToNumber(buffer))
          buffer = ""
        }
        result.append(character)
      }
    }
    if !buffer.isEmpty {
      result += String(chineseRunToNumber(buffer))
    }
    return result
  }

  private static func chineseRunToNumber(_ run: String) -> Int {
    var total = 0
    var section = 0
    var number = 0
    var sawUnit = false
    for character in run {
      if let digit = chineseDigits[character] {
        number = digit
      } else if let unit = chineseUnits[character] {
        sawUnit = true
        if number == 0 { number = 1 }
        section += number * unit
        number = 0
      }
    }
    total = section + number
    if !sawUnit && run.count > 1 {
      // 连续数字（如「一二三」）按位拼接
      var digits = ""
      for character in run {
        if let digit = chineseDigits[character] { digits += String(digit) }
      }
      return Int(digits) ?? total
    }
    return total
  }
}
