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

  public fun finish() {
    _bookProgress.value = emptyMap()
  }
}
