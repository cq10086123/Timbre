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
  fun `re-reporting a book keeps the already scanned count`() {
    val reporter = ScanProgressReporter()

    // the chapter total is reported before and after the directory listing
    reporter.beginBook(bookId, chaptersTotal = 0)
    reporter.chapterScanned(bookId)
    reporter.beginBook(bookId, chaptersTotal = 5)

    assertEquals(
      expected = BookScanProgress(bookId = bookId, chaptersTotal = 5, chaptersScanned = 1),
      actual = reporter.bookProgress.value[bookId],
    )
  }

  @Test
  fun `ignores chapters of unknown books`() {
    val reporter = ScanProgressReporter()

    reporter.chapterScanned(bookId)
    reporter.finishBook(bookId)

    assertEquals(expected = emptyMap(), actual = reporter.bookProgress.value)
  }

  @Test
  fun `reports and clears the errors of a book`() {
    val reporter = ScanProgressReporter()
    val error = BookScanError(bookId = bookId, kind = BookScanError.Kind.Unreachable)

    reporter.reportError(error)
    assertEquals(expected = mapOf(bookId to error), actual = reporter.bookErrors.value)

    reporter.clearError(bookId)
    assertEquals(expected = emptyMap(), actual = reporter.bookErrors.value)
  }

  @Test
  fun `finishing a scan keeps the errors`() {
    val reporter = ScanProgressReporter()
    val error = BookScanError(bookId = bookId, kind = BookScanError.Kind.Unreachable)

    reporter.reportError(error)
    reporter.finish()

    // the card of a book that could not be imported shows its error until the
    // next scan, which is long after the scan that reported it ended
    assertEquals(expected = mapOf(bookId to error), actual = reporter.bookErrors.value)
    assertEquals(expected = emptyMap(), actual = reporter.bookProgress.value)
  }

  @Test
  fun `beginning a scan clears the errors of the previous one`() {
    val reporter = ScanProgressReporter()

    reporter.reportError(BookScanError(bookId = bookId, kind = BookScanError.Kind.Unreachable))
    reporter.beginScan()

    // a stale error of a book that isn't part of the library anymore must not
    // stay on the shelf forever
    assertEquals(expected = emptyMap(), actual = reporter.bookErrors.value)
  }

  @Test
  fun `fraction is one once all chapters were scanned`() {
    val reporter = ScanProgressReporter()

    reporter.beginBook(bookId, chaptersTotal = 3)
    repeat(3) { reporter.chapterScanned(bookId) }

    assertEquals(expected = 1f, actual = reporter.bookProgress.value[bookId]?.fraction)
  }
}
