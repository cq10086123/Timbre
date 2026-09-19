package voice.features.playbackScreen

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChapterRangesTest {

  @Test
  fun `small books have no ranges`() {
    assertTrue(chapterRanges(itemCount = 29, activeItemIndex = 0).isEmpty())
  }

  @Test
  fun `books at the threshold get a single range`() {
    val ranges = chapterRanges(itemCount = 30, activeItemIndex = -1)

    assertEquals(1, ranges.size)
    assertEquals(1, ranges.first().firstNumber)
    assertEquals(30, ranges.first().lastNumber)
  }

  @Test
  fun `ranges cover all items without gaps`() {
    val ranges = chapterRanges(itemCount = 1653, activeItemIndex = -1)

    assertEquals(34, ranges.size)
    assertEquals(1, ranges.first().firstNumber)
    assertEquals(1653, ranges.last().lastNumber)
    assertEquals(1651, ranges.last().firstNumber)
    val contiguous =
      ranges.zipWithNext { previous, next ->
        assertEquals(previous.lastNumber + 1, next.firstNumber)
        assertEquals(previous.startIndex + previous.lastNumber - previous.firstNumber + 1, next.startIndex)
        true
      }
    assertTrue(contiguous.all { it })
  }

  @Test
  fun `the range containing the active item is flagged`() {
    val ranges = chapterRanges(itemCount = 1653, activeItemIndex = 621)

    val flagged = ranges.filter { it.containsCurrent }
    assertEquals(1, flagged.size)
    assertEquals(601, flagged.single().firstNumber)
    assertEquals(650, flagged.single().lastNumber)
    assertEquals(600, flagged.single().startIndex)
  }

  @Test
  fun `no range is flagged when no item is active`() {
    val ranges = chapterRanges(itemCount = 100, activeItemIndex = -1)

    assertTrue(ranges.none { it.containsCurrent })
  }

  @Test
  fun `very large books use bigger ranges`() {
    val ranges = chapterRanges(itemCount = 2500, activeItemIndex = -1)

    assertEquals(25, ranges.size)
    assertEquals(1, ranges.first().firstNumber)
    assertEquals(100, ranges.first().lastNumber)
    assertEquals(2401, ranges.last().firstNumber)
    assertEquals(2500, ranges.last().lastNumber)
  }
}
