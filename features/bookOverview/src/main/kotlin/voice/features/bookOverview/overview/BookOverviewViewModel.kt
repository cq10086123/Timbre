package voice.features.bookOverview.overview

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.core.net.toUri
import androidx.datastore.core.DataStore
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import voice.core.common.DispatcherProvider
import voice.core.common.MainScope
import voice.core.common.comparator.sortedNaturally
import voice.core.data.Book
import voice.core.data.BookId
import voice.core.data.GridMode
import voice.core.data.KioskModeDemoData
import voice.core.data.repo.BookContentRepo
import voice.core.data.repo.BookRepository
import voice.core.data.repo.internals.dao.RecentBookSearchDao
import voice.core.data.store.CurrentBookStore
import voice.core.data.store.GridModeStore
import voice.core.data.withoutAudioFileExtension
import voice.core.featureflag.ExperimentalPlaybackPersistenceQualifier
import voice.core.featureflag.FeatureFlag
import voice.core.featureflag.FolderPickerInSettingsFeatureFlagQualifier
import voice.core.featureflag.KioskModeFeatureFlagQualifier
import voice.core.logging.api.Logger
import voice.core.online.OnlineBook
import voice.core.online.OnlinePlaybackCatalog
import voice.core.online.OnlineSourceBooksStore
import voice.core.online.OnlineUri
import voice.core.playback.LivePlaybackState
import voice.core.playback.PlayerController
import voice.core.playback.overlay
import voice.core.playback.playstate.PlayStateManager
import voice.core.scanner.BookScanError
import voice.core.scanner.BookScanProgress
import voice.core.scanner.DeviceHasStoragePermissionBug
import voice.core.scanner.MediaScanTrigger
import voice.core.search.BookSearch
import voice.core.ui.GridCount
import voice.core.update.UpdateNotifier
import voice.features.bookOverview.di.BookOverviewScope
import voice.features.bookOverview.search.BookSearchViewState
import voice.navigation.Destination
import voice.navigation.Navigator
import voice.navigation.Origin

@SingleIn(BookOverviewScope::class)
@Inject
class BookOverviewViewModel(
  private val repo: BookRepository,
  private val mediaScanner: MediaScanTrigger,
  private val playStateManager: PlayStateManager,
  private val playerController: PlayerController,
  @CurrentBookStore
  private val currentBookStoreDataStore: DataStore<BookId?>,
  @GridModeStore
  private val gridModeStore: DataStore<GridMode>,
  private val gridCount: GridCount,
  private val navigator: Navigator,
  private val recentBookSearchDao: RecentBookSearchDao,
  private val search: BookSearch,
  private val contentRepo: BookContentRepo,
  private val deviceHasStoragePermissionBug: DeviceHasStoragePermissionBug,
  @FolderPickerInSettingsFeatureFlagQualifier
  private val folderPickerInSettingsFeatureFlag: FeatureFlag<Boolean>,
  @ExperimentalPlaybackPersistenceQualifier
  private val experimentalPlaybackPersistenceFeatureFlag: FeatureFlag<Boolean>,
  @KioskModeFeatureFlagQualifier
  private val kioskModeFeatureFlag: FeatureFlag<Boolean>,
  val updateNotifier: UpdateNotifier,
  @OnlineSourceBooksStore private val onlineBooksStore: DataStore<List<OnlineBook>>,
  private val onlinePlaybackCatalog: OnlinePlaybackCatalog,
  dispatcherProvider: DispatcherProvider,
) {

  private val scope = MainScope(dispatcherProvider)
  private var searchActive by mutableStateOf(false)
  private var query by mutableStateOf("")

  fun attach() {
    mediaScanner.scan()
  }

  @Composable
  internal fun state(): BookOverviewViewState {
    val kioskMode = remember { kioskModeFeatureFlag.get() }
    if (kioskMode) return kioskModeState()

    val playState = remember { playStateManager.playStateFlow }
      .collectAsState(initial = PlayStateManager.PlayState.Paused).value
    val hasStoragePermissionBug = remember { deviceHasStoragePermissionBug.hasBug }
      .collectAsState().value
    val localBooks = remember { repo.flow() }
      .collectAsState(initial = emptyList()).value
    val storedOnlineBooks = remember { onlineBooksStore.data }
      .collectAsState(initial = emptyList()).value
    // online books live in their own store; synthesize display books so they
    // show up on the shelf and open the player like any other book
    val books = localBooks + storedOnlineBooks.map { onlinePlaybackCatalog.localBook(it) }
    val currentBookId = remember { currentBookStoreDataStore.data }
      .collectAsState(initial = null).value
    val scannerActive = remember { mediaScanner.scannerActive }
      .collectAsState(initial = false).value
    // the scanner reports every analyzed chapter; sampling keeps the shelf
    // from recomposing continuously while still animating the progress line.
    // The value that is already known is emitted before the first sampling
    // interval elapses, otherwise a shelf opened during an import stays
    // without its cards for one interval
    val importingBooks = remember {
      val progress = mediaScanner.bookScanProgress
      progress
        .sample(IMPORT_PROGRESS_UPDATE_INTERVAL_MS)
        .onStart { emit(progress.value) }
    }.collectAsState(initial = emptyMap()).value
    // books that could not be imported (partially): shown on their card with a
    // retry, so a failing import never stays invisible
    val scanErrors = remember { mediaScanner.bookScanErrors }
      .collectAsState(initial = emptyMap()).value
    // Default matches StoreModule so the shelf can paint before DataStore
    // delivers its first value instead of showing a blank Loading frame.
    val gridMode = remember { gridModeStore.data }
      .collectAsState(initial = GridMode.FOLLOW_DEVICE).value

    val noBooks = !scannerActive && books.isEmpty()

    val layoutMode = when (gridMode) {
      GridMode.LIST -> BookOverviewLayoutMode.List
      GridMode.GRID -> BookOverviewLayoutMode.Grid
      GridMode.FOLLOW_DEVICE -> if (gridCount.useGridAsDefault()) {
        BookOverviewLayoutMode.Grid
      } else {
        BookOverviewLayoutMode.List
      }
    }

    val bookSearchViewState = bookSearchViewState(layoutMode)
    val experimentalPlaybackPersistence = experimentalPlaybackPersistenceFeatureFlag.get()
    val livePlaybackState: State<LivePlaybackState?> = if (experimentalPlaybackPersistence && currentBookId != null) {
      remember(currentBookId) {
        playerController.livePlaybackStateFlow(currentBookId)
      }.collectAsState(null)
    } else {
      remember { mutableStateOf(null) }
    }

    val onlineCovers = storedOnlineBooks.associate {
      BookId(OnlineUri.buildBookUri(it.source, it.bookId)) to it.cover
    }
    val groupedBooks = books
      .groupBy {
        it.category
      }
      .mapValues { (category, books) ->
        books
          .sortedWith(category.comparator)
          .associate { book ->
            val itemVs = book.itemViewState(
              currentBookId = currentBookId,
              livePlaybackState = { livePlaybackState.value },
              importProgress = importingBooks[book.id],
              importError = scanErrors[book.id],
            )
            val cover: String? = onlineCovers[book.id]
            val finalItemVs = if (cover.isNullOrBlank()) {
              itemVs
            } else {
              remember(cover) { derivedStateOf { itemVs.value.copy(cover = cover) } }
            }
            book.id to finalItemVs
          }
      }
      .toSortedMap()

    val booksWithPendingImports = groupedBooks.withPendingImports(
      importingBooks = importingBooks,
      scanErrors = scanErrors,
    )

    return BookOverviewViewState(
      layoutMode = layoutMode,
      books = booksWithPendingImports,
      playButtonState = if (playState == PlayStateManager.PlayState.Playing) {
        BookOverviewViewState.PlayButtonState.Playing
      } else {
        BookOverviewViewState.PlayButtonState.Paused
      }.takeIf { currentBookId != null },
      showAddBookHint = if (hasStoragePermissionBug) {
        false
      } else {
        noBooks
      },
      showSearchIcon = books.isNotEmpty(),
      isLoading = scannerActive,
      searchActive = searchActive,
      searchViewState = bookSearchViewState,
      showStoragePermissionBugCard = hasStoragePermissionBug,
      showFolderPickerIcon = !folderPickerInSettingsFeatureFlag.get(),
    )
  }

  @Composable
  private fun bookSearchViewState(layoutMode: BookOverviewLayoutMode): BookSearchViewState {
    return if (searchActive) {
      val recentBookSearch = remember {
        recentBookSearchDao.recentBookSearches()
      }.collectAsState(initial = emptyList()).value.reversed()
      var searchBooks by remember {
        mutableStateOf(emptyList<BookOverviewItemViewState>())
      }
      LaunchedEffect(query) {
        searchBooks = search.search(query).map { it.toItemViewState() }
      }
      val suggestedAuthors: List<String> by produceState(initialValue = emptyList()) {
        value = contentRepo.all()
          .filter { it.isActive }
          .mapNotNull { it.author }
          .toSet()
          .sortedNaturally()
      }

      val bookSearchViewState = if (query.isNotBlank()) {
        BookSearchViewState.SearchResults(
          query = query,
          books = searchBooks,
          layoutMode = layoutMode,
        )
      } else {
        BookSearchViewState.EmptySearch(
          recentQueries = recentBookSearch,
          suggestedAuthors = suggestedAuthors,
          query = query,
        )
      }
      bookSearchViewState
    } else {
      BookSearchViewState.EmptySearch(
        recentQueries = emptyList(),
        suggestedAuthors = emptyList(),
        query = query,
      )
    }
  }

  private fun kioskModeState(): BookOverviewViewState {
    return BookOverviewViewState(
      layoutMode = BookOverviewLayoutMode.List,
      books = mapOf(
        BookOverviewCategory.CURRENT to KioskModeDemoData.demoAudiobooks.associate { book ->
          book.id to mutableStateOf(
            BookOverviewItemViewState(
              name = book.title,
              author = book.author,
              cover = book.coverUrl,
              progress = book.progress / 100F,
              id = book.id,
              remainingTime = book.remaining,
            ),
          )
        },
      ),
      playButtonState = BookOverviewViewState.PlayButtonState.Paused,
      showAddBookHint = false,
      showSearchIcon = true,
      isLoading = false,
      searchActive = false,
      searchViewState = BookSearchViewState.EmptySearch(
        recentQueries = emptyList(),
        suggestedAuthors = KioskModeDemoData.demoAudiobooks.map { it.author },
        query = "",
      ),
      showStoragePermissionBugCard = false,
      showFolderPickerIcon = false,
    )
  }

  fun onSettingsClick() {
    navigator.goTo(Destination.Settings)
  }

  fun openLatestRelease() {
    navigator.goTo(Destination.Website(UpdateNotifier.RELEASE_PAGE_URL))
  }

  fun onBookClick(id: BookId) {
    navigator.goTo(Destination.Playback(id))
  }

  fun onBookFolderClick() {
    navigator.goTo(Destination.FolderPicker)
  }

  /**
   * Retries the import of a book that reported an error. The scan itself works
   * on the whole library, so retrying one book re-checks the others as well -
   * which is what the user wants after a connection problem.
   */
  fun retryImport(id: BookId) {
    Logger.d("Retrying the import of $id")
    // an explicit retry must not be swallowed by the throttle that keeps the
    // shelf from re-scanning on every appearance
    mediaScanner.scan(restartIfScanning = true)
  }

  fun onAddBookClick() {
    navigator.goTo(Destination.AddContent(Origin.Default))
  }

  fun onSearchActiveChange(active: Boolean) {
    if (active && !searchActive) {
      query = ""
    }
    this.searchActive = active
  }

  fun onSearchQueryChange(query: String) {
    this.query = query
  }

  fun onClearSearchHistory() {
    scope.launch {
      recentBookSearchDao.clear()
    }
  }

  fun onSearchBookClick(id: BookId) {
    val query = query.trim()
    if (query.isNotBlank()) {
      scope.launch {
        recentBookSearchDao.add(query)
      }
    }
    searchActive = false
    navigator.goTo(Destination.Playback(id))
  }

  fun playPause() {
    playerController.playPause()
  }

  fun onPermissionBugCardClick() {
    if (Build.VERSION.SDK_INT >= 30) {
      navigator.goTo(
        Destination.Activity(
          Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
            .setData("package:com.android.externalstorage".toUri()),
        ),
      )
    }
  }
}

@Composable
private fun Book.itemViewState(
  currentBookId: BookId?,
  livePlaybackState: () -> LivePlaybackState?,
  importProgress: BookScanProgress?,
  importError: BookScanError?,
): State<BookOverviewItemViewState> {
  if (id != currentBookId) {
    return rememberUpdatedState(toItemViewState(importProgress, importError))
  }
  val currentPlaybackState by rememberUpdatedState(livePlaybackState)
  val currentImportProgress by rememberUpdatedState(importProgress)
  val currentImportError by rememberUpdatedState(importError)
  return remember(this, currentBookId) {
    derivedStateOf {
      val livePlayback = currentPlaybackState()
      if (livePlayback != null) {
        overlay(livePlayback)
      } else {
        this
      }.toItemViewState(currentImportProgress, currentImportError)
    }
  }
}

/**
 * Cards for books that are being imported but not stored yet (no chapter was
 * analyzed so far), and for books that failed to import. They show up the
 * moment the scan discovers the book, so the shelf reflects an import
 * instantly instead of staying blank until the first chapters were parsed -
 * and a book that never made it into the library still shows up with its
 * error instead of silently vanishing again.
 */
private fun Map<BookOverviewCategory, Map<BookId, State<BookOverviewItemViewState>>>.withPendingImports(
  importingBooks: Map<BookId, BookScanProgress>,
  scanErrors: Map<BookId, BookScanError>,
): Map<BookOverviewCategory, Map<BookId, State<BookOverviewItemViewState>>> {
  val pendingBookIds = importingBooks.keys + scanErrors.keys
  val pendingBooks = pendingBookIds.filter { bookId ->
    values.none { it.containsKey(bookId) }
  }
  if (pendingBooks.isEmpty()) {
    // Ensure BookOverviewCategory.CURRENT always exists in the map
    val current = getOrDefault(BookOverviewCategory.CURRENT, emptyMap())
    return if (containsKey(BookOverviewCategory.CURRENT)) {
      this
    } else {
      this + (BookOverviewCategory.CURRENT to current)
    }
  }
  val placeholders = pendingBooks.associateWith { bookId ->
    mutableStateOf(
      bookId.toPendingItemViewState(
        progress = importingBooks[bookId],
        error = scanErrors[bookId],
      ),
    )
  }
  val current = getOrDefault(BookOverviewCategory.CURRENT, emptyMap())
  // BookId is not Comparable, so sorting needs an explicit comparator
  val merged = current.plus(placeholders).toSortedMap(compareBy { it.value })
  return this + (BookOverviewCategory.CURRENT to merged)
}

private fun BookId.toPendingItemViewState(
  progress: BookScanProgress?,
  error: BookScanError?,
): BookOverviewItemViewState {
  // the book name isn't known before the first chapter was analyzed, so fall
  // back to the folder or file name of the book uri
  val lastSegment = value.toUri().lastPathSegment ?: value
  val name = lastSegment.substringAfterLast('/')
    .withoutAudioFileExtension()
    .ifBlank { lastSegment }
  return BookOverviewItemViewState(
    name = name,
    author = null,
    cover = null,
    progress = 0F,
    id = this,
    remainingTime = "",
    importProgress = progress,
    importError = error,
  )
}
private const val IMPORT_PROGRESS_UPDATE_INTERVAL_MS = 250L
