package voice.core.playback.prefetch

import android.app.Application
import androidx.test.core.app.ApplicationProvider.getApplicationContext
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import voice.core.common.DispatcherProvider
import voice.core.data.Book
import voice.core.data.BookId
import voice.core.data.Chapter
import voice.core.data.ChapterId
import voice.core.data.ChapterMark
import voice.core.data.MarkData
import voice.core.data.repo.BookRepository
import voice.core.logging.api.LogWriter
import voice.core.logging.api.Logger
import voice.core.playback.MemoryDataStore
import voice.core.playback.session.search.book
import voice.core.webdav.WebDavCacheSettings
import voice.core.webdav.WebDavDataSourceFactory
import voice.core.webdav.WebDavPlaybackCache
import java.time.Instant
import kotlin.test.Test
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class PrefetchSchedulerTest {

  init {
    Logger.install(
      object : LogWriter {
        override fun log(
          severity: Logger.Severity,
          message: String,
          throwable: Throwable?,
        ) {
          println("$severity: $message")
        }
      },
    )
  }

  private val scope = TestScope()

  private val bookId = BookId("https://nas.example.com/dav/book")
  private val settingsStore = MemoryDataStore(WebDavCacheSettings.Disabled)

  private val playbackCache = mockk<WebDavPlaybackCache>(relaxed = true) {
    every { settings() } returns settingsStore
    // no cache: the prefetch loop returns right after the book was resolved,
    // which is exactly the observable this test asserts on
    every { cacheOrNull() } returns null
  }

  private val bookRepository = mockk<BookRepository>()

  private val scheduler = PrefetchScheduler(
    playbackCache = playbackCache,
    webDavDataSourceFactory = mockk(relaxed = true),
    bookRepository = bookRepository,
    playStateManager = mockk(relaxed = true),
    playbackIoGate = mockk(relaxed = true),
    context = getApplicationContext<Application>(),
    currentBookStore = MemoryDataStore<BookId?>(bookId),
    dispatcherProvider = DispatcherProvider(io = UnconfinedTestDispatcher(scope.testScheduler)),
  )

  @Test
  fun `re enabling the prefetch restarts the loop`() = scope.runTest {
    val webDavBook = webDavBook()
    coEvery { bookRepository.get(bookId) } returns webDavBook

    scheduler.start()
    runCurrent()
    // the cache and the prefetch are disabled: the loop must not touch the book
    coVerify(exactly = 0) { val _ = bookRepository.get(any()) }

    // the user turns the cache back on: the settings are part of the
    // collection, so the loop restarts without a book switch
    settingsStore.updateData { WebDavCacheSettings() }
    advanceUntilIdle()

    coVerify(exactly = 1) { val _ = bookRepository.get(bookId) }
  }

  private fun webDavBook(): Book {
    val chapter = Chapter(
      id = ChapterId("https://nas.example.com/dav/book/01.mp3"),
      name = "chapter",
      duration = 60_000L,
      fileLastModified = Instant.EPOCH,
      markData = listOf(MarkData(0L, Uuid.random().toString())),
      fileSize = 0,
    )
    return book(listOf(chapter), bookId)
  }
}
