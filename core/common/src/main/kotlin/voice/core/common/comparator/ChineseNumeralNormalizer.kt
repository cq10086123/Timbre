package voice.core.common.comparator

/**
 * Turns Chinese numeral runs that follow a counter prefix such as 第/卷/季
 * (e.g. "第二十章", "卷一百零一") into ASCII numbers ("第20章", "卷101"), so the
 * regular natural ordering places 第十章 before 第二十章.
 *
 * Runs without a counter prefix are left untouched to avoid mistaking numerals
 * used as words (e.g. "一千个为什么") for episode numbers.
 */
internal object ChineseNumeralNormalizer {

  private const val MAX_VALUE = 999_999_999_999L

  private val triggers = setOf(
    '第', '卷', '季', '部', '篇', '章', '回', '节', '课', '辑', '册',
  )

  private val digits = mapOf(
    '零' to 0L,
    '〇' to 0L,
    '一' to 1L,
    '二' to 2L,
    '两' to 2L,
    '三' to 3L,
    '四' to 4L,
    '五' to 5L,
    '六' to 6L,
    '七' to 7L,
    '八' to 8L,
    '九' to 9L,
  )

  private val multipliers = setOf('十', '百', '千', '万', '亿')

  private fun Char.isChineseNumeral(): Boolean = this in digits || this in multipliers

  fun normalize(input: String): String {
    if (input.none { it in digits || it in multipliers }) return input

    val builder = StringBuilder(input.length)
    var index = 0
    while (index < input.length) {
      val char = input[index]
      val runStart = index
      if (char.isChineseNumeral() && index > 0 && input[index - 1] in triggers) {
        while (index < input.length && input[index].isChineseNumeral()) {
          index++
        }
        val run = input.substring(runStart, index)
        val parsed = parse(run)
        if (parsed != null) {
          builder.append(parsed)
          continue
        }
      }
      builder.append(char)
      index++
    }
    return builder.toString()
  }

  private fun parse(run: String): Long? {
    var total = 0L
    var section = 0L
    var pendingDigit = 0L
    var seen = false
    for (char in run) {
      val digit = digits[char]
      when {
        digit != null -> {
          // consecutive digits are read position by position, e.g.
          // "一〇一" = 101, "二零二六" = 2026
          pendingDigit = pendingDigit * 10 + digit
          seen = true
        }
        char == '十' -> {
          section += (if (pendingDigit == 0L) 1L else pendingDigit) * 10
          pendingDigit = 0L
          seen = true
        }
        char == '百' -> {
          section += pendingDigit * 100
          pendingDigit = 0L
        }
        char == '千' -> {
          section += pendingDigit * 1_000
          pendingDigit = 0L
        }
        char == '万' -> {
          total += (section + pendingDigit) * 10_000
          section = 0L
          pendingDigit = 0L
        }
        char == '亿' -> {
          total = (total + section + pendingDigit) * 100_000_000
          section = 0L
          pendingDigit = 0L
        }
      }
      if (total > MAX_VALUE) return null
    }
    val result = total + section + pendingDigit
    return if (seen && result <= MAX_VALUE) result else null
  }
}
