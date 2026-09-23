package voice.core.online

import androidx.datastore.core.DataStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Ignore
import voice.core.common.DispatcherProvider
import voice.core.data.BookId
import voice.core.logging.api.LogWriter
import voice.core.logging.api.Logger
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

class OnlineBookCacheManagerTest {

  init {
    Logger.install(
      object : LogWriter {
        override fun log(
          severity: Logger.Severity,
          message: String,
          throwable: Throwable?,
        ) {
          println("$severity: $message ${throwable?.toString().orEmpty()}")
        }
      },
    )
  }

  private lateinit var server: MockWebServer
  private val catalog = mockk<OnlinePlaybackCatalog>()
  private val service = mockk<OnlineSourceService>()
  private lateinit var manager: OnlineBookCacheManager
  private lateinit var fileCache: OnlineChapterFileCache

  private val bookId = BookId(OnlineUri.buildBookUri("A", "b1"))

  @Before
  fun setUp() {
    server = MockWebServer()
    server.start()
  }

  @After
  fun tearDown() {
    server.close()
  }

  @Test
  fun `caches the next chapters starting at the current one`() = runTest {
    fileCache = OnlineChapterFileCache(createTempDirectory("online-cache").toFile())
    manager = OnlineBookCacheManager(
      catalog = catalog,
      service = service,
      fileCache = fileCache,
      httpClient = OkHttpClient(),
      baseUrlStore = FakeStore(""),
      tokenStore = FakeStore(""),
      dispatcherProvider = DispatcherProvider(
        main = UnconfinedTestDispatcher(testScheduler),
        mainImmediate = UnconfinedTestDispatcher(testScheduler),
        io = UnconfinedTestDispatcher(testScheduler),
      ),
    )
    coEvery { catalog.lookupOnlineBook("A", "b1") } returns OnlineBook(
      source = "A",
      bookId = "b1",
      title = "T",
      currentChapterId = "c2",
      chapters = listOf(
        OnlineChapter(id = "c1", title = "第1集"),
        OnlineChapter(id = "c2", title = "第2集"),
        OnlineChapter(id = "c3", title = "第3集"),
      ),
    )
    repeat(2) {
      server.enqueue(MockResponse.Builder().code(200).body("fake-audio-bytes".repeat(64)).build())
    }
    coEvery { catalog.resolveStreamUrl(any()) } returns server.url("/audio.mp3").toString()

    manager.cacheUpcoming(bookId, 2)
    awaitIdle()

    assertTrue(fileCache.isCached(OnlineChapterRef("A", "b1", "c2")))
    assertTrue(fileCache.isCached(OnlineChapterRef("A", "b1", "c3")))
    assertEquals(2, fileCache.cachedFileCount("A", "b1"))
    val state = manager.states.first()[bookId.value]
    assertEquals(2, state?.done)
    assertEquals(0, state?.failed)
  }

  @Test
  fun `main catalog submits one episode per server task`() = runTest {
    fileCache = OnlineChapterFileCache(createTempDirectory("online-cache").toFile())
    manager = OnlineBookCacheManager(
      catalog = catalog,
      service = service,
      fileCache = fileCache,
      httpClient = OkHttpClient(),
      baseUrlStore = FakeStore(""),
      tokenStore = FakeStore(""),
      dispatcherProvider = DispatcherProvider(
        main = UnconfinedTestDispatcher(testScheduler),
        mainImmediate = UnconfinedTestDispatcher(testScheduler),
        io = UnconfinedTestDispatcher(testScheduler),
      ),
    )
    val mainBookId = BookId(OnlineUri.buildBookUri("main", "b1"))
    coEvery { catalog.lookupOnlineBook("main", "b1") } returns OnlineBook(
      source = "main",
      bookId = "b1",
      title = "T",
      currentChapterId = "c2",
      chapters = listOf(
        OnlineChapter(id = "c1", title = "第1集"),
        OnlineChapter(id = "c2", title = "第2集"),
        OnlineChapter(id = "c3", title = "第3集"),
      ),
    )
    repeat(2) {
      server.enqueue(MockResponse.Builder().code(200).body("fake-audio-bytes".repeat(64)).build())
    }
    coEvery { catalog.resolveStreamUrl(any()) } returns server.url("/audio.mp3").toString()
    coEvery { service.submitDownload(any(), any(), any()) } returns "task"

    manager.cacheUpcoming(mainBookId, 2, 0)
    awaitIdle()

    // one single-episode server task per chapter, not one task for the window
    coVerify { service.submitDownload("b1", 2, 2) }
    coVerify { service.submitDownload("b1", 3, 3) }
  }

  @Test
  fun `clearBook drops the cached files`() = runTest {
    fileCache = OnlineChapterFileCache(createTempDirectory("online-cache").toFile())
    manager = OnlineBookCacheManager(
      catalog = catalog,
      service = service,
      fileCache = fileCache,
      httpClient = OkHttpClient(),
      baseUrlStore = FakeStore(""),
      tokenStore = FakeStore(""),
      dispatcherProvider = DispatcherProvider(
        main = UnconfinedTestDispatcher(testScheduler),
        mainImmediate = UnconfinedTestDispatcher(testScheduler),
        io = UnconfinedTestDispatcher(testScheduler),
      ),
    )
    val ref = OnlineChapterRef("A", "b1", "c1")
    val tmp = fileCache.tmpFileFor(ref)
    tmp.parentFile?.mkdirs()
    tmp.writeBytes(ByteArray(8))
    val _ = fileCache.completeDownload(ref)

    assertTrue(manager.clearBook(bookId))
    assertEquals(0, fileCache.cachedFileCount("A", "b1"))
  }

  /**
   * Wall-clock wait: the manager downloads on real dispatchers while runTest
   * owns the virtual clock.
   */
  private suspend fun awaitIdle() = withContext(Dispatchers.IO) {
    val mark = TimeSource.Monotonic.markNow()
    while (true) {
      val state = manager.states.value[bookId.value]
      if (state != null && !state.downloading) return@withContext
      check(mark.elapsedNow() < 15_000.milliseconds) {
        "the cache job never finished: $state"
      }
      Thread.sleep(25)
    }
  }

  private class FakeStore<T>(initial: T) : DataStore<T> {
    private val state = MutableStateFlow(initial)
    override val data: Flow<T> = state
    override suspend fun updateData(transform: suspend (T) -> T): T {
      val next = transform(state.value)
      state.value = next
      return next
    }
  }
}
