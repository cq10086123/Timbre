package voice.core.online

import androidx.datastore.core.DataStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import voice.core.common.DispatcherProvider
import voice.core.common.PlaybackIoGate
import voice.core.data.BookId
import voice.core.logging.api.LogWriter
import voice.core.logging.api.Logger
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
  private lateinit var jobsStore: FakeStore<List<OnlineCacheJob>>

  /** Switched by the metered tests to simulate mobile data. */
  private var metered = false

  private val bookId = BookId(OnlineUri.buildBookUri("A", "b1"))

  @Before
  fun setUp() {
    server = MockWebServer()
    server.start()
    fileCache = OnlineChapterFileCache(createTempDirectory("online-cache").toFile())
    metered = false
  }

  @After
  fun tearDown() {
    server.close()
  }

  @Test
  fun `caches the next chapters starting at the current one`() = runTest {
    manager = createManager()
    stubBook()
    enqueueAudio()
    coEvery { catalog.resolveStreamUrl(any()) } returns OnlineAudio(url = server.url("/audio.mp3").toString())

    manager.cacheUpcoming(bookId, 2)
    await("both chapters to be cached") { fileCache.cachedFileCount("A", "b1") == 2 }
    await("the job to report itself as finished") { state()?.downloading == false }

    assertTrue(fileCache.isCached(OnlineChapterRef("A", "b1", "c2")), "the chapter in progress is cached first")
    assertTrue(fileCache.isCached(OnlineChapterRef("A", "b1", "c3")))
    assertEquals(2, state()?.done)
    assertEquals(0, state()?.failed)
  }

  @Test
  fun `shows a progress state the moment the job starts`() = runTest {
    manager = createManager()
    stubBook()
    enqueueAudio()
    coEvery { catalog.resolveStreamUrl(any()) } returns OnlineAudio(url = server.url("/audio.mp3").toString())

    manager.cacheUpcoming(bookId, 2)

    // the dialog must show a bar right after the button press, not only after
    // the first network round trip
    val started = assertNotNull(manager.states.value[bookId.value])
    assertTrue(started.downloading)
    assertEquals(2, started.total)
    await("the job to report itself as finished") { state()?.downloading == false }
  }

  @Test
  fun `clearBook drops the cached files`() = runTest {
    manager = createManager()
    val ref = OnlineChapterRef("A", "b1", "c1")
    val tmp = fileCache.tmpFileFor(ref)
    tmp.parentFile?.mkdirs()
    tmp.writeBytes(ByteArray(8))
    val _ = fileCache.completeDownload(ref)

    assertTrue(manager.clearBook(bookId))
    assertEquals(0, fileCache.cachedFileCount("A", "b1"))
  }

  @Test
  fun `asks before caching on mobile data`() = runTest {
    metered = true
    manager = createManager()
    stubBook()
    enqueueAudio()
    coEvery { catalog.resolveStreamUrl(any()) } returns OnlineAudio(url = server.url("/audio.mp3").toString())

    manager.cacheUpcoming(bookId, 2)

    val confirmation = awaitConfirmation()
    assertEquals(bookId.value, confirmation.bookUri)
    assertEquals(2, confirmation.chapters)
    assertEquals(true, state()?.awaitingConfirmation)
    // nothing is resolved (and nothing downloaded) before the answer
    coVerify(exactly = 0) { val _ = catalog.resolveStreamUrl(any()) }
    assertEquals(0, fileCache.cachedFileCount("A", "b1"))

    manager.confirmMeteredCache()
    await("the confirmed chapters to be cached") { fileCache.cachedFileCount("A", "b1") == 2 }

    assertNull(manager.meteredConfirmation.value)
    assertEquals(2, state()?.done)
  }

  @Test
  fun `declining the confirmation drops the job`() = runTest {
    metered = true
    manager = createManager()
    stubBook()
    coEvery { catalog.resolveStreamUrl(any()) } returns OnlineAudio(url = server.url("/audio.mp3").toString())

    manager.cacheUpcoming(bookId, 2)
    val _ = awaitConfirmation()
    manager.declineMeteredCache()
    await("the declined job to disappear") { state() == null && jobsStore.current.isEmpty() }

    assertNull(manager.meteredConfirmation.value)
    assertEquals(0, fileCache.cachedFileCount("A", "b1"))
    coVerify(exactly = 0) { val _ = catalog.resolveStreamUrl(any()) }
    // a declined job must not come back on the next start
    assertTrue(jobsStore.current.isEmpty())
  }

  @Test
  fun `resumes a persisted job on app start`() = runTest {
    manager = createManager(jobs = listOf(OnlineCacheJob(bookUri = bookId.value, count = 2)))
    stubBook()
    enqueueAudio()
    coEvery { catalog.resolveStreamUrl(any()) } returns OnlineAudio(url = server.url("/audio.mp3").toString())

    manager.resumePending()
    await("the resumed chapters to be cached") { fileCache.cachedFileCount("A", "b1") == 2 }
    await("the finished job to leave the store") { jobsStore.current.isEmpty() }

    assertTrue(fileCache.isCached(OnlineChapterRef("A", "b1", "c2")))
    assertTrue(fileCache.isCached(OnlineChapterRef("A", "b1", "c3")))
  }

  @Test
  fun `a job taken over on mobile data asks again`() = runTest {
    metered = true
    manager = createManager(
      jobs = listOf(OnlineCacheJob(bookUri = bookId.value, count = 1, attempts = 1)),
    )
    stubBook()
    enqueueAudio()
    coEvery { catalog.resolveStreamUrl(any()) } returns OnlineAudio(url = server.url("/audio.mp3").toString())

    manager.resumePending()

    // a cache started on wifi must not continue on mobile data unasked
    val confirmation = awaitConfirmation()
    assertEquals(bookId.value, confirmation.bookUri)
    coVerify(exactly = 0) { val _ = catalog.resolveStreamUrl(any()) }

    manager.confirmMeteredCache()
    await("the chapter to be cached after the confirmation") {
      fileCache.cachedFileCount("A", "b1") == 1
    }

    assertTrue(fileCache.isCached(OnlineChapterRef("A", "b1", "c2")))
  }

  @Test
  fun `keeps an aborted job for the next start`() = runTest {
    manager = createManager()
    stubBook(chapters = 6, currentChapterId = "c1")
    coEvery { catalog.resolveStreamUrl(any()) } returns null

    manager.cacheUpcoming(bookId, 6)
    await("the job to abort") { state()?.downloading == false }

    // five chapters failed in a row: the job aborts but stays persisted, so
    // the next app start picks the work up again
    assertEquals(5, state()?.failed)
    assertEquals(1, jobsStore.current.size)
    assertEquals(1, jobsStore.current.first().attempts, "a run without progress counts as one attempt")
  }

  @Test
  fun `does not give up on a job that still downloads chapters`() = runTest {
    manager = createManager(
      jobs = listOf(OnlineCacheJob(bookUri = bookId.value, count = 6, attempts = 2)),
    )
    stubBook(chapters = 7, currentChapterId = "c1")
    server.enqueue(MockResponse.Builder().code(200).body("fake-audio-bytes".repeat(64)).build())
    repeat(5) { server.enqueue(MockResponse.Builder().code(404).body("").build()) }
    coEvery { catalog.resolveStreamUrl(any()) } returns OnlineAudio(url = server.url("/audio.mp3").toString())

    manager.resumePending()
    await("the job to abort after the failures") { state()?.downloading == false }

    // the first chapter came through, the rest of the window failed
    assertEquals(1, state()?.done)
    assertEquals(5, state()?.failed)
    assertEquals(1, fileCache.cachedFileCount("A", "b1"))
    // progress resets the attempt counter: the job must not be given up on,
    // the next start continues where this run stopped
    assertEquals(1, jobsStore.current.size)
    assertEquals(0, jobsStore.current.first().attempts)
  }

  @Test
  fun `clearing while the metered question is open drops the job`() = runTest {
    metered = true
    manager = createManager()
    stubBook()
    coEvery { catalog.resolveStreamUrl(any()) } returns OnlineAudio(url = server.url("/audio.mp3").toString())

    manager.cacheUpcoming(bookId, 2)
    val _ = awaitConfirmation()

    // the user does not answer at all but clears the cache of the book
    val _ = manager.clearBook(bookId)
    await("the metered question to be dropped") { manager.meteredConfirmation.value == null }
    manager.confirmMeteredCache()
    await("the cleared job to stay gone") { state() == null && jobsStore.current.isEmpty() }

    // neither the clear nor a later confirmation may start a download
    coVerify(exactly = 0) { val _ = catalog.resolveStreamUrl(any()) }
    assertEquals(0, fileCache.cachedFileCount("A", "b1"))
  }

  private fun state(): OnlineCacheState? = manager.states.value[bookId.value]

  private fun enqueueAudio() {
    repeat(2) {
      server.enqueue(MockResponse.Builder().code(200).body("fake-audio-bytes".repeat(64)).build())
    }
  }

  private fun TestScope.createManager(jobs: List<OnlineCacheJob> = emptyList()): OnlineBookCacheManager {
    jobsStore = FakeStore(jobs)
    return OnlineBookCacheManager(
      catalog = catalog,
      service = service,
      fileCache = fileCache,
      httpClient = OkHttpClient(),
      baseUrlStore = FakeStore(""),
      tokenStore = FakeStore(""),
      jobsStore = jobsStore,
      meteredNetworkChecker = MeteredNetworkChecker { metered },
      playbackIoGate = PlaybackIoGate(),
      dispatcherProvider = DispatcherProvider(
        main = UnconfinedTestDispatcher(testScheduler),
        mainImmediate = UnconfinedTestDispatcher(testScheduler),
        io = UnconfinedTestDispatcher(testScheduler),
      ),
    )
  }

  private fun stubBook(
    source: String = "A",
    bookId: String = "b1",
    chapters: Int = 3,
    currentChapterId: String = "c2",
  ) {
    coEvery { catalog.lookupOnlineBook(source, bookId) } returns OnlineBook(
      source = source,
      bookId = bookId,
      title = "T",
      currentChapterId = currentChapterId,
      chapters = (1..chapters).map { index ->
        OnlineChapter(id = "c$index", title = "第${index}集")
      },
    )
  }

  /**
   * Wall-clock wait: the manager downloads on real dispatchers while runTest
   * owns the virtual clock, so the assertions poll the observable outcome
   * instead of waiting for coroutines.
   */
  private suspend fun await(
    description: String,
    condition: () -> Boolean,
  ) = withContext(Dispatchers.IO) {
    val mark = TimeSource.Monotonic.markNow()
    while (!condition()) {
      check(mark.elapsedNow() < 15_000.milliseconds) { "timed out waiting for $description" }
      Thread.sleep(25)
    }
  }

  /** Wall-clock wait for the metered question; the job suspends until it is answered. */
  private suspend fun awaitConfirmation(): OnlineCacheConfirmation = withContext(Dispatchers.IO) {
    val mark = TimeSource.Monotonic.markNow()
    var confirmation = manager.meteredConfirmation.value
    while (confirmation == null) {
      check(mark.elapsedNow() < 15_000.milliseconds) { "no metered confirmation appeared" }
      Thread.sleep(25)
      confirmation = manager.meteredConfirmation.value
    }
    confirmation
  }

  private class FakeStore<T>(initial: T) : DataStore<T> {
    private val state = MutableStateFlow(initial)

    val current: T get() = state.value

    override val data: Flow<T> = state

    override suspend fun updateData(transform: suspend (T) -> T): T {
      val next = transform(state.value)
      state.value = next
      return next
    }
  }
}
