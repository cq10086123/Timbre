package voice.features.bookOverview.onlineActions

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import voice.core.common.DispatcherProvider
import voice.core.common.MainScope
import voice.core.data.BookId
import voice.core.online.OnlineBookCacheManager
import voice.core.online.OnlineCacheState
import voice.core.online.OnlineCachedInfo
import voice.core.online.OnlineChapterRefreshResult
import voice.core.online.OnlinePlaybackCatalog
import voice.features.bookOverview.bottomSheet.BottomSheetItem
import voice.features.bookOverview.bottomSheet.BottomSheetItemViewModel
import voice.features.bookOverview.di.BookOverviewScope

internal sealed interface OnlineRefreshUiState {
  val bookId: BookId

  data class Refreshing(override val bookId: BookId) : OnlineRefreshUiState
  data class Result(
    override val bookId: BookId,
    val result: OnlineChapterRefreshResult,
  ) : OnlineRefreshUiState
}

internal data class OnlineCacheDialogState(
  val bookId: BookId,
  val info: OnlineCachedInfo?,
  val selectedCount: Int = DEFAULT_CACHE_COUNT,
  val customCount: String = "",
  val clearArmed: Boolean = false,
  val loading: Boolean = true,
)

@SingleIn(BookOverviewScope::class)
@ContributesIntoSet(BookOverviewScope::class)
class OnlineBookActionsViewModel(
  private val catalog: OnlinePlaybackCatalog,
  private val cacheManager: OnlineBookCacheManager,
  dispatcherProvider: DispatcherProvider,
) : BottomSheetItemViewModel {

  private val scope = MainScope(dispatcherProvider)

  private val _refreshState = mutableStateOf<OnlineRefreshUiState?>(null)
  internal val refreshState: State<OnlineRefreshUiState?> get() = _refreshState

  private val _cacheDialog = mutableStateOf<OnlineCacheDialogState?>(null)
  internal val cacheDialog: State<OnlineCacheDialogState?> get() = _cacheDialog

  override suspend fun items(bookId: BookId): List<BottomSheetItem> {
    if (!catalog.isOnlineBookId(bookId)) return emptyList()
    return listOf(BottomSheetItem.OnlineRefreshChapters, BottomSheetItem.OnlineCacheBook)
  }

  override suspend fun onItemClick(
    bookId: BookId,
    item: BottomSheetItem,
  ) {
    when (item) {
      BottomSheetItem.OnlineRefreshChapters -> refreshChapters(bookId)
      BottomSheetItem.OnlineCacheBook -> openCacheDialog(bookId)
      else -> return
    }
  }

  internal fun cacheProgress(bookUri: String): Flow<OnlineCacheState?> {
    return cacheManager.stateFor(bookUri)
  }

  private fun refreshChapters(bookId: BookId) {
    _refreshState.value = OnlineRefreshUiState.Refreshing(bookId)
    scope.launch {
      val result = catalog.refreshChapters(bookId)
      // the dialog may have been dismissed while the source answered: only
      // report when it is still waiting for this book
      val current = _refreshState.value
      if (current is OnlineRefreshUiState.Refreshing && current.bookId == bookId) {
        _refreshState.value = OnlineRefreshUiState.Result(bookId, result)
      }
    }
  }

  internal fun onDismissRefresh() {
    _refreshState.value = null
  }

  private fun openCacheDialog(bookId: BookId) {
    _cacheDialog.value = OnlineCacheDialogState(bookId = bookId, info = null, loading = true)
    reloadCacheInfo(bookId)
  }

  internal fun reloadCacheInfo(bookId: BookId) {
    scope.launch {
      val info = runCatching { cacheManager.cachedInfo(bookId) }.getOrNull()
      val current = _cacheDialog.value
      if (current != null && current.bookId == bookId) {
        _cacheDialog.value = current.copy(info = info, loading = false, clearArmed = false)
      }
    }
  }

  internal fun onDismissCache() {
    _cacheDialog.value = null
  }

  internal fun onSelectCount(count: Int) {
    val current = _cacheDialog.value ?: return
    _cacheDialog.value = current.copy(selectedCount = count, customCount = "")
  }

  internal fun onCustomCountChange(text: String) {
    val current = _cacheDialog.value ?: return
    _cacheDialog.value = current.copy(customCount = text.filter { it.isDigit() }.take(5))
  }

  internal fun onStartCache() {
    val current = _cacheDialog.value ?: return
    val info = current.info
    val remaining = if (info == null || info.totalChapters <= 0) {
      Int.MAX_VALUE
    } else {
      (info.totalChapters - info.currentIndex).coerceAtLeast(1)
    }
    val count = current.customCount.toIntOrNull()
      ?.takeIf { it > 0 }
      ?: current.selectedCount
    cacheManager.cacheUpcoming(current.bookId, count.coerceAtMost(remaining))
  }

  internal fun onCancelCache() {
    val current = _cacheDialog.value ?: return
    cacheManager.cancel(current.bookId)
    reloadCacheInfo(current.bookId)
  }

  /**
   * Two taps: the first arms the button, the second clears. Clearing only
   * drops the downloaded files; the book and its progress stay on the shelf.
   */
  internal fun onClearCache() {
    val current = _cacheDialog.value ?: return
    if (!current.clearArmed) {
      _cacheDialog.value = current.copy(clearArmed = true)
      return
    }
    scope.launch {
      runCatching { cacheManager.clearBook(current.bookId) }
      reloadCacheInfo(current.bookId)
    }
  }
}

internal const val DEFAULT_CACHE_COUNT = 100

internal val CACHE_COUNT_PRESETS = listOf(50, 100, 200, 500)
