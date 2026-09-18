package voice.core.scanner

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import voice.core.data.BookId

/**
 * The import progress of a single book. It is shown on the card of the book
 * while the book is being scanned.
 */
public data class BookScanProgress(
  val bookId: BookId,
  val chaptersTotal: Int,
  val chaptersScanned: Int,
) {
  public val fraction: Float
    get() = if (chaptersTotal == 0) 0f else chaptersScanned.toFloat() / chaptersTotal
}

@SingleIn(AppScope::class)
@Inject
public class ScanProgressReporter {

  private val _bookProgress = MutableStateFlow<Map<BookId, BookScanProgress>>(emptyMap())
  public val bookProgress: StateFlow<Map<BookId, BookScanProgress>> = _bookProgress.asStateFlow()

  private val _bookErrors = MutableStateFlow<Map<BookId, BookScanError>>(emptyMap())
  public val bookErrors: StateFlow<Map<BookId, BookScanError>> = _bookErrors.asStateFlow()

  public fun beginBook(
    bookId: BookId,
    chaptersTotal: Int,
  ) {
    _bookProgress.update { progress ->
      // re-reporting a book (for example once the chapter total is known after
      // the directory listing) must not reset the already scanned count
      val scanned = progress[bookId]?.chaptersScanned ?: 0
      progress + (bookId to BookScanProgress(bookId = bookId, chaptersTotal = chaptersTotal, chaptersScanned = scanned))
    }
  }

  public fun chapterScanned(bookId: BookId) {
    _bookProgress.update { progress ->
      val bookProgress = progress[bookId] ?: return@update progress
      progress + (
        bookId to bookProgress.copy(chaptersScanned = bookProgress.chaptersScanned + 1)
        )
    }
  }

  public fun finishBook(bookId: BookId) {
    _bookProgress.update { progress ->
      progress - bookId
    }
  }

  /** Reports that [BookScanError.bookId] could not be imported completely. */
  public fun reportError(error: BookScanError) {
    _bookErrors.update { errors ->
      errors + (error.bookId to error)
    }
  }

  /** Removes a previously reported error, e.g. after a successful rescan. */
  public fun clearError(bookId: BookId) {
    if (bookId !in _bookErrors.value) return
    _bookErrors.update { errors ->
      errors - bookId
    }
  }

  /**
   * Clears everything a scan reports. A new scan starts from scratch: errors
   * of a previous one are re-reported if the problem is still there.
   */
  public fun beginScan() {
    _bookProgress.value = emptyMap()
    _bookErrors.value = emptyMap()
  }

  /**
   * The scan itself is over. The progress is gone with it, but the errors stay:
   * the card of a book that could not be imported shows them (with a retry)
   * until the next scan.
   */
  public fun finish() {
    _bookProgress.value = emptyMap()
  }
}
