package voice.features.playbackScreen

import voice.features.playbackScreen.BookPlayDialogViewState.SelectChapterDialog.RangeViewState

/**
 * Books with fewer items are scrolled through easily, so the range jump grid stays hidden for them.
 */
private const val MIN_ITEMS_FOR_RANGES = 30

private const val DEFAULT_RANGE_SIZE = 50

/**
 * Above this item count even the range grid would grow too long, so bigger blocks are used.
 */
private const val LARGE_BOOK_ITEM_COUNT = 2000

private const val LARGE_BOOK_RANGE_SIZE = 100

/**
 * Splits the flat chapter list into blocks of consecutive numbers, e.g. "601 - 650".
 * Tapping such a block scrolls the list to the first item of the block.
 */
internal fun chapterRanges(
  itemCount: Int,
  activeItemIndex: Int,
): List<RangeViewState> {
  if (itemCount < MIN_ITEMS_FOR_RANGES) return emptyList()
  val rangeSize = if (itemCount > LARGE_BOOK_ITEM_COUNT) LARGE_BOOK_RANGE_SIZE else DEFAULT_RANGE_SIZE
  return buildList {
    var start = 0
    while (start < itemCount) {
      val end = minOf(start + rangeSize, itemCount)
      add(
        RangeViewState(
          firstNumber = start + 1,
          lastNumber = end,
          startIndex = start,
          containsCurrent = activeItemIndex in start until end,
        ),
      )
      start = end
    }
  }
}
