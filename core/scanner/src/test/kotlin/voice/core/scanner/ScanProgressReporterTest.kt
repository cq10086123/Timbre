package voice.core.scanner

import voice.core.data.BookId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScanProgressReporterTest {

  private val bookId = BookId("book")
  private val otherBookId = BookId("other")

  @Test
  fun `reports the progress of a single book`() {
    val reporter = ScanProgressReporter()

    reporter.beginBook(bookId, chaptersTotal = 10)

    val begun = reporter.bookProgress.value[bookId]
    assertEquals(
      expected = BookScanProgress(bookId = bookId, chaptersTotal = 10, chaptersScanned = 0),
      actual = begun,
    )
    assertEquals(expected = 0f, actual = begun?.fraction)

    repeat(5) { reporter.chapterScanned(bookId) }

    val halfWay = reporter.bookProgress.value[bookId]
    assertEquals(
      expected = BookScanProgress(bookId = bookId, chaptersTotal = 10, chaptersScanned = 5),
      actual = halfWay,
    )
    assertEquals(expected = 0.5f, actual = halfWay?.fraction)

    reporter.finishBook(bookId)
    assertEquals(expected = null, actual = reporter.bookProgress.value[bookId])
  }

  @Test
  fun `tracks several books independently`() {
    val reporter = ScanProgressReporter()

    reporter.beginBook(bookId, chaptersTotal = 2)
    reporter.beginBook(otherBookId, chaptersTotal = 1)

    reporter.chapterScanned(otherBookId)
    assertEquals(
      expected = BookScanProgress(bookId = bookId, chaptersTotal = 2, chaptersScanned = 0),
      actual = reporter.bookProgress.value[bookId],
    )
    assertEquals(
      expected = BookScanProgress(bookId = otherBookId, chaptersTotal = 1, chaptersScanned = 1),
      actual = reporter.bookProgress.value[otherBookId],
    )

    reporter.finishBook(otherBookId)
    assertTrue(bookId in reporter.bookProgress.value)
    assertEquals(expected = null, actual = reporter.bookProgress.value[otherBookId])
  }

  @Test
  fun `ignores chapters of unknown books`() {
    val reporter = ScanProgressReporter()

    reporter.chapterScanned(bookId)
    reporter.finishBook(bookId)

    assertEquals(expected = emptyMap(), actual = reporter.bookProgress.value)
  }

  @Test
  fun `fraction is one once all chapters were scanned`() {
    val reporter = ScanProgressReporter()

    reporter.beginBook(bookId, chaptersTotal = 3)
    repeat(3) { reporter.chapterScanned(bookId) }

    assertEquals(expected = 1f, actual = reporter.bookProgress.value[bookId]?.fraction)
  }
}
