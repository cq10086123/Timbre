package voice.core.scanner

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

public data class ScanProgress(
  val booksTotal: Int,
  val booksScanned: Int,
  val chaptersTotal: Int,
  val chaptersScanned: Int,
) {
  public val fraction: Float
    get() = if (chaptersTotal == 0) 0f else chaptersScanned.toFloat() / chaptersTotal
}

@SingleIn(AppScope::class)
@Inject
public class ScanProgressReporter {

  private val _progress = MutableStateFlow<ScanProgress?>(null)
  public val progress: StateFlow<ScanProgress?> = _progress.asStateFlow()

  public fun begin(
    booksTotal: Int,
    chaptersTotal: Int,
  ) {
    _progress.value = ScanProgress(
      booksTotal = booksTotal,
      booksScanned = 0,
      chaptersTotal = chaptersTotal,
      chaptersScanned = 0,
    )
  }

  public fun chapterScanned() {
    _progress.update { progress ->
      progress?.copy(chaptersScanned = progress.chaptersScanned + 1)
    }
  }

  public fun bookScanned() {
    _progress.update { progress ->
      progress?.copy(booksScanned = progress.booksScanned + 1)
    }
  }

  public fun finish() {
    _progress.value = null
  }
}
