package voice.features.bookOverview.overview

import androidx.datastore.core.DataStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.molecule.RecompositionMode
import app.cash.molecule.launchMolecule
import app.cash.turbine.test
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.runner.RunWith
import voice.core.common.DispatcherProvider
import voice.core.data.Book
import voice.core.data.BookId
import voice.core.data.GridMode
import voice.core.data.KioskModeDemoData
import voice.core.data.repo.BookContentRepo
import voice.core.data.repo.BookRepository
import voice.core.data.repo.internals.dao.RecentBookSearchDao
import voice.core.featureflag.MemoryFeatureFlag
import voice.core.playback.LivePlaybackState
import voice.core.playback.PlayerController
import voice.core.playback.overlay
import voice.core.playback.playstate.PlayStateManager
import voice.core.scanner.BookScanProgress
import voice.core.scanner.DeviceHasStoragePermissionBug
import voice.core.scanner.MediaScanTrigger
import voice.core.search.BookSearch
import voice.core.ui.GridCount
import voice.features.bookOverview.book
import voice.navigation.Destination
import voice.navigation.Navigator
import kotlin.test.Test
import kotlin.test.assertEquals

// robolectric: the placeholder cards derive their name from the book uri
@RunWith(AndroidJUnit4::class)
class BookOverviewViewModelTest {

  private val testDispatcher = UnconfinedTestDispatcher()
  private val dispatcherProvider = DispatcherProvider(testDispatcher, testDispatcher, testDispatcher)

  @Test
  fun `state updates the current book item from live playback`() = runTest {
    val currentBook = book(name = "Current", time = 1_000)
    val otherBook = book(name = "Other", time = 2_000)
    val livePlaybackFlow = MutableStateFlow<LivePlaybackState?>(null)
    val viewModel = BookOverviewViewModel(
      repo = mockk<BookRepository> {
        every { flow() } returns MutableStateFlow(listOf(currentBook, otherBook))
      },
      mediaScanner = mockk<MediaScanTrigger> {
        every { scannerActive } returns MutableStateFlow(false)
        every { bookScanProgress } returns MutableStateFlow(emptyMap())
        every { scan(any()) } just Runs
      },
      playStateManager = PlayStateManager(),
      playerController = mockk<PlayerController> {
        every { livePlaybackStateFlow(currentBook.id) } returns livePlaybackFlow
      },
      currentBookStoreDataStore = MemoryDataStore(currentBook.id),
      gridModeStore = MemoryDataStore(GridMode.LIST),
      gridCount = mockk<GridCount> {
        every { useGridAsDefault() } returns false
      },
      navigator = mockk<Navigator>(),
      recentBookSearchDao = mockk<RecentBookSearchDao> {
        every { recentBookSearches() } returns MutableStateFlow(emptyList())
      },
      search = mockk<BookSearch> {
        coEvery { search(any()) } returns emptyList()
      },
      contentRepo = mockk<BookContentRepo>(),
      deviceHasStoragePermissionBug = mockk<DeviceHasStoragePermissionBug> {
        every { hasBug } returns MutableStateFlow(false)
      },
      folderPickerInSettingsFeatureFlag = MemoryFeatureFlag(false),
      experimentalPlaybackPersistenceFeatureFlag = MemoryFeatureFlag(true),
      kioskModeFeatureFlag = MemoryFeatureFlag(false),
      dispatcherProvider = dispatcherProvider,
    )

    backgroundScope.launchMolecule(RecompositionMode.Immediate) {
      viewModel.state()
    }.test {
      assertEquals(expected = BookOverviewViewState.Loading, actual = awaitItem())
      val initial = awaitItem()
      val initialCurrentItem = initial.currentBook(currentBook.id)
      val initialOtherItem = initial.currentBook(otherBook.id)
      val initialKeys = initial.books.getValue(BookOverviewCategory.CURRENT).keys.toList()

      assertEquals(expected = currentBook.toItemViewState(), actual = initialCurrentItem)
      assertEquals(expected = otherBook.toItemViewState(), actual = initialOtherItem)

      val livePlaybackState = LivePlaybackState(
        bookId = currentBook.id,
        chapterId = currentBook.chapters.first().id,
        positionMs = 6_000,
        isPlaying = true,
        playbackSpeed = 1F,
      )
      livePlaybackFlow.value = livePlaybackState
      yield()

      assertEquals(expected = initialKeys, actual = initial.books.getValue(BookOverviewCategory.CURRENT).keys.toList())
      assertEquals(expected = currentBook.overlay(livePlaybackState).toItemViewState(), actual = initial.currentBook(currentBook.id))
      assertEquals(expected = initialOtherItem, actual = initial.currentBook(otherBook.id))
      expectNoEvents()
    }
  }

  @Test
  fun `state shows importing books that are not stored yet as placeholder cards`() = runTest {
    val importingBookId = BookId("content://x/凡人修仙传")
    val importProgress = BookScanProgress(
      bookId = importingBookId,
      chaptersTotal = 3,
      chaptersScanned = 1,
    )
    val viewModel = viewModel(
      books = emptyList(),
      bookScanProgress = mapOf(importingBookId to importProgress),
    )

    backgroundScope.launchMolecule(RecompositionMode.Immediate) {
      viewModel.state()
    }.test {
      assertEquals(expected = BookOverviewViewState.Loading, actual = awaitItem())
      val state = awaitItem()

      // the card appears as soon as the scan discovered the book, before any
      // chapter was stored, so the shelf never stays blank during an import
      val placeholder = state.books.getValue(BookOverviewCategory.CURRENT).getValue(importingBookId).value
      assertEquals(expected = "凡人修仙传", actual = placeholder.name)
      assertEquals(expected = importProgress, actual = placeholder.importProgress)
      assertEquals(expected = 0F, actual = placeholder.progress)
    }
  }

  @Test
  fun `state does not add a placeholder for books that are already stored`() = runTest {
    val storedBook = book(name = "Stored", time = 1_000)
    val importProgress = BookScanProgress(bookId = storedBook.id, chaptersTotal = 2, chaptersScanned = 1)
    val viewModel = viewModel(
      books = listOf(storedBook),
      bookScanProgress = mapOf(storedBook.id to importProgress),
    )

    backgroundScope.launchMolecule(RecompositionMode.Immediate) {
      viewModel.state()
    }.test {
      assertEquals(expected = BookOverviewViewState.Loading, actual = awaitItem())
      val state = awaitItem()

      assertEquals(expected = listOf(storedBook.id), actual = state.books.getValue(BookOverviewCategory.CURRENT).keys.toList())
      assertEquals(expected = importProgress, actual = state.currentBook(storedBook.id).importProgress)
    }
  }

  @Test
  fun `state uses demo books in kiosk mode`() = runTest {
    val viewModel = BookOverviewViewModel(
      repo = mockk<BookRepository> {
        every { flow() } returns MutableStateFlow(emptyList())
      },
      mediaScanner = mockk<MediaScanTrigger> {
        every { scannerActive } returns MutableStateFlow(false)
        every { bookScanProgress } returns MutableStateFlow(emptyMap())
        every { scan(any()) } just Runs
      },
      playStateManager = PlayStateManager(),
      playerController = mockk(),
      currentBookStoreDataStore = MemoryDataStore(null),
      gridModeStore = MemoryDataStore(GridMode.LIST),
      gridCount = mockk<GridCount> {
        every { useGridAsDefault() } returns false
      },
      navigator = mockk<Navigator>(),
      recentBookSearchDao = mockk<RecentBookSearchDao> {
        every { recentBookSearches() } returns MutableStateFlow(emptyList())
      },
      search = mockk<BookSearch> {
        coEvery { search(any()) } returns emptyList()
      },
      contentRepo = mockk<BookContentRepo>(),
      deviceHasStoragePermissionBug = mockk<DeviceHasStoragePermissionBug> {
        every { hasBug } returns MutableStateFlow(false)
      },
      folderPickerInSettingsFeatureFlag = MemoryFeatureFlag(false),
      experimentalPlaybackPersistenceFeatureFlag = MemoryFeatureFlag(false),
      kioskModeFeatureFlag = MemoryFeatureFlag(true),
      dispatcherProvider = dispatcherProvider,
    )

    backgroundScope.launchMolecule(RecompositionMode.Immediate) {
      viewModel.state()
    }.test {
      val state = awaitItem()
      assertEquals(
        expected = KioskModeDemoData.demoAudiobooks.map {
          it.id
        },
        actual = state.books.getValue(BookOverviewCategory.CURRENT).keys.toList(),
      )
      assertEquals(expected = "Echoes of Tomorrow", actual = state.currentBook(KioskModeDemoData.currentlyPlaying.id).name)
    }
  }

  @Test
  fun `folder picker icon is hidden when folder picker in settings flag is true`() = runTest {
    val viewModel = viewModel(
      folderPickerInSettingsFeatureFlag = MemoryFeatureFlag(true),
    )

    backgroundScope.launchMolecule(RecompositionMode.Immediate) {
      viewModel.state()
    }.test {
      assertEquals(expected = BookOverviewViewState.Loading, actual = awaitItem())
      assertEquals(expected = false, actual = awaitItem().showFolderPickerIcon)
    }
  }

  @Test
  fun `folder picker icon is shown once when flag is false`() = runTest {
    val viewModel = viewModel(
      folderPickerInSettingsFeatureFlag = MemoryFeatureFlag(false),
    )

    backgroundScope.launchMolecule(RecompositionMode.Immediate) {
      viewModel.state()
    }.test {
      assertEquals(expected = BookOverviewViewState.Loading, actual = awaitItem())
      assertEquals(expected = true, actual = awaitItem().showFolderPickerIcon)
    }
  }

  @Test
  fun `folder picker click navigates to the folder picker`() = runTest {
    val navigator = mockk<Navigator>(relaxed = true)
    val viewModel = viewModel(
      navigator = navigator,
      folderPickerInSettingsFeatureFlag = MemoryFeatureFlag(false),
    )

    backgroundScope.launchMolecule(RecompositionMode.Immediate) {
      viewModel.state()
    }.test {
      assertEquals(expected = BookOverviewViewState.Loading, actual = awaitItem())
      awaitItem()

      viewModel.onBookFolderClick()

      verify {
        navigator.goTo(Destination.FolderPicker)
      }
    }
  }

  private fun BookOverviewViewState.currentBook(bookId: BookId): BookOverviewItemViewState {
    return books.getValue(BookOverviewCategory.CURRENT).getValue(bookId).value
  }

  private fun viewModel(
    folderPickerInSettingsFeatureFlag: MemoryFeatureFlag<Boolean>,
    navigator: Navigator = mockk(),
    books: List<Book> = emptyList(),
    bookScanProgress: Map<BookId, BookScanProgress> = emptyMap(),
  ): BookOverviewViewModel {
    return BookOverviewViewModel(
      repo = mockk<BookRepository> {
        every { flow() } returns MutableStateFlow(books)
      },
      mediaScanner = mockk<MediaScanTrigger> {
        every { scannerActive } returns MutableStateFlow(false)
        every { bookScanProgress } returns MutableStateFlow(bookScanProgress)
        every { scan(any()) } just Runs
      },
      playStateManager = PlayStateManager(),
      playerController = mockk(),
      currentBookStoreDataStore = MemoryDataStore(null),
      gridModeStore = MemoryDataStore(GridMode.LIST),
      gridCount = mockk<GridCount> {
        every { useGridAsDefault() } returns false
      },
      navigator = navigator,
      recentBookSearchDao = mockk<RecentBookSearchDao> {
        every { recentBookSearches() } returns MutableStateFlow(emptyList())
      },
      search = mockk<BookSearch> {
        coEvery { search(any()) } returns emptyList()
      },
      contentRepo = mockk<BookContentRepo>(),
      deviceHasStoragePermissionBug = mockk<DeviceHasStoragePermissionBug> {
        every { hasBug } returns MutableStateFlow(false)
      },
      folderPickerInSettingsFeatureFlag = folderPickerInSettingsFeatureFlag,
      experimentalPlaybackPersistenceFeatureFlag = MemoryFeatureFlag(false),
      kioskModeFeatureFlag = MemoryFeatureFlag(false),
      dispatcherProvider = dispatcherProvider,
    )
  }
}

private class MemoryDataStore<T>(initial: T) : DataStore<T> {

  private val value = MutableStateFlow(initial)

  override val data: Flow<T> get() = value

  override suspend fun updateData(transform: suspend (t: T) -> T): T {
    return value.updateAndGet { transform(it) }
  }
}
