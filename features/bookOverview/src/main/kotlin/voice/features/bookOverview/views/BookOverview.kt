package voice.features.bookOverview.views

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue.Expanded
import androidx.compose.material3.SheetValue.Hidden
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.retain.retain
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavEntry
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides
import kotlinx.coroutines.launch
import voice.core.common.rootGraphAs
import voice.core.data.BookId
import voice.core.ui.PlayButton
import voice.core.ui.VoiceTheme
import voice.core.ui.playButtonSharedBoundsModifier
import voice.features.bookOverview.bottomSheet.BottomSheetContent
import voice.features.bookOverview.bottomSheet.BottomSheetItem
import voice.features.bookOverview.deleteBook.DeleteBookDialog
import voice.features.bookOverview.di.BookOverviewGraph
import voice.features.bookOverview.editTitle.EditBookTitleDialog
import voice.features.bookOverview.onlineActions.OnlineCacheDialog
import voice.features.bookOverview.onlineActions.OnlineRefreshDialog
import voice.features.bookOverview.overview.BookOverviewCategory
import voice.features.bookOverview.overview.BookOverviewItemViewState
import voice.features.bookOverview.overview.BookOverviewLayoutMode
import voice.features.bookOverview.overview.BookOverviewViewState
import voice.features.bookOverview.search.BookSearchViewState
import voice.features.bookOverview.views.topbar.BookOverviewTopBar
import voice.navigation.Destination
import voice.navigation.NavEntryProvider
import kotlin.uuid.Uuid

@ContributesTo(AppScope::class)
interface BookOverviewProvider {

  @Provides
  @IntoSet
  fun bookOverviewNavEntryProvider(): NavEntryProvider<*> = NavEntryProvider<Destination.BookOverview> { key ->
    NavEntry(key) {
      BookOverviewScreen()
    }
  }
}

@Composable
fun BookOverviewScreen(modifier: Modifier = Modifier) {
  val bookGraph = retain<BookOverviewGraph> {
    rootGraphAs<BookOverviewGraph.Factory.Provider>()
      .bookOverviewGraphProviderFactory.create()
  }
  val bookOverviewViewModel = bookGraph.bookOverviewViewModel
  val editBookTitleViewModel = bookGraph.editBookTitleViewModel
  val bottomSheetViewModel = bookGraph.bottomSheetViewModel
  val deleteBookViewModel = bookGraph.deleteBookViewModel
  val fileCoverViewModel = bookGraph.fileCoverViewModel
  val onlineBookActionsViewModel = bookGraph.onlineBookActionsViewModel

  LaunchedEffect(Unit) {
    bookOverviewViewModel.attach()
  }
  val viewState = bookOverviewViewModel.state()

  val scope = rememberCoroutineScope()

  val getContentLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.GetContent(),
    onResult = { uri ->
      if (uri != null) {
        fileCoverViewModel.onImagePicked(uri)
      }
    },
  )

  var showBottomSheet by remember { mutableStateOf(false) }
  BookOverview(
    viewState = viewState,
    onSettingsClick = bookOverviewViewModel::onSettingsClick,
    onBookClick = bookOverviewViewModel::onBookClick,
    onBookLongClick = { bookId ->
      bottomSheetViewModel.bookSelected(bookId)
      showBottomSheet = true
    },
    onRetryImport = bookOverviewViewModel::retryImport,
    onBookFolderClick = bookOverviewViewModel::onBookFolderClick,
    onAddBookClick = bookOverviewViewModel::onAddBookClick,
    onPlayButtonClick = bookOverviewViewModel::playPause,
    onSearchActiveChange = bookOverviewViewModel::onSearchActiveChange,
    onSearchQueryChange = bookOverviewViewModel::onSearchQueryChange,
    onSearchBookClick = bookOverviewViewModel::onSearchBookClick,
    onClearSearchHistory = bookOverviewViewModel::onClearSearchHistory,
    onPermissionBugCardClick = bookOverviewViewModel::onPermissionBugCardClick,
  )
  val deleteBookViewState = deleteBookViewModel.state.value
  if (deleteBookViewState != null) {
    DeleteBookDialog(
      viewState = deleteBookViewState,
      onDismiss = deleteBookViewModel::onDismiss,
      onConfirmDeletion = deleteBookViewModel::onConfirmDeletion,
      onDeleteCheckBoxCheck = deleteBookViewModel::onDeleteCheckBoxCheck,
    )
  }
  val update = bookOverviewViewModel.updateNotifier.update.collectAsState().value
  if (update != null) {
    UpdateDialog(
      update = update,
      onUpdateClick = {
        bookOverviewViewModel.openLatestRelease()
        bookOverviewViewModel.updateNotifier.onUpdatePageOpened()
      },
      onDismissClick = {
        scope.launch {
          bookOverviewViewModel.updateNotifier.dismiss()
        }
      },
    )
  }
  val editBookTitleState = editBookTitleViewModel.state.value
  if (editBookTitleState != null) {
    EditBookTitleDialog(
      onDismissEditTitleClick = editBookTitleViewModel::onDismissEditTitle,
      onConfirmEditTitle = editBookTitleViewModel::onConfirmEditTitle,
      viewState = editBookTitleState,
      onUpdateEditTitle = editBookTitleViewModel::onUpdateEditTitle,
    )
  }
  val onlineRefreshState = onlineBookActionsViewModel.refreshState.value
  if (onlineRefreshState != null) {
    OnlineRefreshDialog(
      viewState = onlineRefreshState,
      onDismiss = onlineBookActionsViewModel::onDismissRefresh,
    )
  }
  val onlineCacheState = onlineBookActionsViewModel.cacheDialog.value
  if (onlineCacheState != null) {
    val progress = remember(onlineCacheState.bookId) {
      onlineBookActionsViewModel.cacheProgress(onlineCacheState.bookId.value)
    }.collectAsState(initial = null).value
    // a finished job changed what is cached: reload the dialog numbers
    LaunchedEffect(progress?.downloading) {
      if (progress != null && !progress.downloading) {
        onlineBookActionsViewModel.reloadCacheInfo(onlineCacheState.bookId)
      }
    }
    OnlineCacheDialog(
      viewState = onlineCacheState,
      progress = progress,
      onSelectCount = onlineBookActionsViewModel::onSelectCount,
      onCustomCountChange = onlineBookActionsViewModel::onCustomCountChange,
      onStartCache = onlineBookActionsViewModel::onStartCache,
      onCancelCache = onlineBookActionsViewModel::onCancelCache,
      onClearCache = onlineBookActionsViewModel::onClearCache,
      onDismiss = onlineBookActionsViewModel::onDismissCache,
    )
  }

  if (showBottomSheet) {
    val sheetState = rememberBottomSheetState(
      initialValue = Hidden,
      enabledValues = setOf(Hidden, Expanded),
    )
    ModalBottomSheet(
      modifier = modifier,
      sheetState = sheetState,
      content = {
        BottomSheetContent(
          state = bottomSheetViewModel.state.value,
          onItemClick = { item ->
            if (item == BottomSheetItem.FileCover) {
              getContentLauncher.launch("image/*")
            }
            scope.launch {
              sheetState.hide()
              bottomSheetViewModel.onItemClick(item)
              showBottomSheet = false
            }
          },
        )
      },
      onDismissRequest = {
        showBottomSheet = false
      },
    )
  }
}

@Composable
internal fun BookOverview(
  viewState: BookOverviewViewState,
  onSettingsClick: () -> Unit,
  onBookClick: (BookId) -> Unit,
  onBookLongClick: (BookId) -> Unit,
  onRetryImport: (BookId) -> Unit,
  onBookFolderClick: () -> Unit,
  onAddBookClick: () -> Unit,
  onPlayButtonClick: () -> Unit,
  onSearchActiveChange: (Boolean) -> Unit,
  onSearchQueryChange: (String) -> Unit,
  onSearchBookClick: (BookId) -> Unit,
  onClearSearchHistory: () -> Unit,
  onPermissionBugCardClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
  Scaffold(
    modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
    topBar = {
      BookOverviewTopBar(
        viewState = viewState,
        onBookFolderClick = onBookFolderClick,
        onSettingsClick = onSettingsClick,
        onActiveChange = onSearchActiveChange,
        onQueryChange = onSearchQueryChange,
        onSearchBookClick = onSearchBookClick,
        onClearSearchHistory = onClearSearchHistory,
      )
    },
    floatingActionButton = {
      if (viewState.playButtonState != null) {
        PlayButton(
          modifier = Modifier.navigationBarsPadding(),
          playing = viewState.playButtonState == BookOverviewViewState.PlayButtonState.Playing,
          fabSize = 56.dp,
          iconSize = 24.dp,
          onPlayClick = onPlayButtonClick,
          sharedElementModifier = Modifier.playButtonSharedBoundsModifier(),
        )
      }
    },
    contentWindowInsets = WindowInsets(0, 0, 0, 0),
  ) { contentPadding ->
    Box(
      Modifier
        .padding(contentPadding)
        .consumeWindowInsets(contentPadding),
    ) {
      val shelfEmpty = viewState.books.values.none { it.isNotEmpty() }
      if (shelfEmpty &&
        !viewState.showStoragePermissionBugCard &&
        !viewState.searchActive
      ) {
        // a running import is indicated by the progress on the book cards, so
        // the shelf shouldn't stay blank while the first books are imported
        EmptyShelf(onAddClick = onAddBookClick)
      } else {
        when (viewState.layoutMode) {
          BookOverviewLayoutMode.List -> {
            ListBooks(
              books = viewState.books,
              onBookClick = onBookClick,
              onBookLongClick = onBookLongClick,
              onRetryImport = onRetryImport,
              showPermissionBugCard = viewState.showStoragePermissionBugCard,
              onPermissionBugCardClick = onPermissionBugCardClick,
            )
          }
          BookOverviewLayoutMode.Grid -> {
            GridBooks(
              books = viewState.books,
              onBookClick = onBookClick,
              onBookLongClick = onBookLongClick,
              onRetryImport = onRetryImport,
              showPermissionBugCard = viewState.showStoragePermissionBugCard,
              onPermissionBugCardClick = onPermissionBugCardClick,
            )
          }
        }
      }
    }
  }
}

@Suppress("ktlint:compose:preview-public-check")
@Preview
@Composable
fun BookOverviewPreview(
  @PreviewParameter(BookOverviewPreviewParameterProvider::class)
  viewState: BookOverviewViewState,
) {
  VoiceTheme {
    BookOverview(
      viewState = viewState,
      onSettingsClick = {},
      onBookClick = {},
      onBookLongClick = {},
      onRetryImport = {},
      onBookFolderClick = {},
      onAddBookClick = {},
      onPlayButtonClick = {},
      onSearchActiveChange = {},
      onSearchQueryChange = {},
      onSearchBookClick = {},
      onClearSearchHistory = {},
      onPermissionBugCardClick = {},
    )
  }
}

internal class BookOverviewPreviewParameterProvider : PreviewParameterProvider<BookOverviewViewState> {

  fun book(): BookOverviewItemViewState {
    return BookOverviewItemViewState(
      name = "Book",
      author = "Author",
      cover = null,
      progress = 0.8F,
      id = BookId(Uuid.random().toString()),
      remainingTime = "01:04",
    )
  }

  override val values = sequenceOf(
    BookOverviewViewState(
      books = mapOf(
        BookOverviewCategory.CURRENT to buildMap {
          repeat(10) {
            put(
              BookId(Uuid.random().toString()),
              mutableStateOf(book()),
            )
          }
        },
        BookOverviewCategory.FINISHED to buildMap {
          repeat(2) {
            put(
              BookId(Uuid.random().toString()),
              mutableStateOf(book()),
            )
          }
        },
      ),
      layoutMode = BookOverviewLayoutMode.List,
      playButtonState = BookOverviewViewState.PlayButtonState.Paused,
      showAddBookHint = false,
      showSearchIcon = true,
      isLoading = true,
      searchActive = true,
      searchViewState = BookSearchViewState.EmptySearch(
        suggestedAuthors = emptyList(),
        recentQueries = emptyList(),
        query = "",
      ),
      showStoragePermissionBugCard = false,
      showFolderPickerIcon = true,
    ),
  )
}
