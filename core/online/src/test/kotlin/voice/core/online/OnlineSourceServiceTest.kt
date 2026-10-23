package voice.core.online

import androidx.datastore.core.DataStore
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
import voice.core.common.DispatcherProvider
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

class OnlineSourceServiceTest {

  private val testDispatcher = UnconfinedTestDispatcher()
  private val dispatcherProvider = DispatcherProvider(testDispatcher, testDispatcher, testDispatcher)
  private val chapterStore = OnlineChapterStore(createTempDirectory("online-chapters").toFile())
  private val coverStore = OnlineCoverStore(createTempDirectory("online-covers").toFile())
  private val booksStore = FakeBooksStore()
  private val service = OnlineSourceService(
    FakeStore(false),
    FakeStore(""),
    FakeStore(""),
    FakeStore(""),
    booksStore,
    chapterStore,
    coverStore,
    OkHttpClient(),
    mockk(),
    dispatcherProvider,
    emptySet(),
  )

  @Test
  fun `re-adding keeps position skip settings and measured durations`() = runTest {
    service.addToShelf(
      OnlineBook(
        source = "A",
        bookId = "b1",
        title = "T",
        currentChapterId = "c2",
        positionMs = 42_000L,
        skipIntroMs = 5_000L,
        skipOutroMs = 3_000L,
        chapters = listOf(
          OnlineChapter(id = "c1", title = "第1集", durationSeconds = 1800),
          OnlineChapter(id = "c2", title = "第2集", durationSeconds = 1750),
        ),
      ),
    )

    // the search dialog hands over a fresh copy without any local state
    service.addToShelf(
      OnlineBook(
        source = "A",
        bookId = "b1",
        title = "T",
        chapters = listOf(
          OnlineChapter(id = "c1", title = "第1集", durationSeconds = 0),
          OnlineChapter(id = "c2", title = "第2集", durationSeconds = 0),
          OnlineChapter(id = "c3", title = "第3集", durationSeconds = 1700),
        ),
      ),
    )

    val stored = booksStore.data.first().single()
    assertEquals("c2", stored.currentChapterId)
    assertEquals(42_000L, stored.positionMs)
    assertEquals(5_000L, stored.skipIntroMs)
    assertEquals(3_000L, stored.skipOutroMs)
    // the chapter list lives in its own file since the split
    assertEquals(emptyList(), stored.chapters)
    val chapters = chapterStore.chapters("A::b1")
    assertEquals(3, chapters.size)
    assertEquals(1800, chapters.single { it.id == "c1" }.durationSeconds)
    assertEquals(1700, chapters.single { it.id == "c3" }.durationSeconds)
  }

  @Test
  fun `addToShelf downloads the cover and rewrites it to a local file`() = runTest {
    val server = MockWebServer()
    server.start()
    val coverBytes = "fake-png-bytes".repeat(16)
    server.enqueue(MockResponse.Builder().code(200).body(coverBytes).build())

    val service = OnlineSourceService(
      FakeStore(false),
      FakeStore(""),
      FakeStore(""),
      FakeStore(""),
      booksStore,
      chapterStore,
      coverStore,
      OkHttpClient(),
      mockk(),
      dispatcherProvider,
      emptySet(),
    )
    service.addToShelf(
      OnlineBook(
        source = "A",
        bookId = "b1",
        title = "T",
        cover = server.url("/cover.jpg").toString(),
      ),
    )

    // the download runs on the background cover scope: wait wall-clock
    withContext(Dispatchers.IO) {
      val mark = TimeSource.Monotonic.markNow()
      while (booksStore.data.first().singleOrNull()?.cover?.startsWith("file:") != true) {
        check(mark.elapsedNow() < 5_000.milliseconds) { "the cover was never rewritten to a local file" }
        Thread.sleep(10)
      }
    }

    val stored = booksStore.data.first().single()
    val file = assertNotNull(coverStore.file("A::b1"))
    assertEquals(file.toURI().toString(), stored.cover)
    assertEquals(coverBytes, file.readText())
    server.close()
  }

  @Test
  fun `a non-image cover response keeps the remote url`() = runTest {
    val server = MockWebServer()
    server.start()
    server.enqueue(MockResponse.Builder().code(200).body("<html>gateway error</html>").build())

    val service = OnlineSourceService(
      FakeStore(false),
      FakeStore(""),
      FakeStore(""),
      FakeStore(""),
      booksStore,
      chapterStore,
      coverStore,
      OkHttpClient(),
      mockk(),
      dispatcherProvider,
      emptySet(),
    )
    service.addToShelf(
      OnlineBook(
        source = "A",
        bookId = "b2",
        title = "T",
        cover = server.url("/cover").toString(),
      ),
    )

    // give the background download time to run; an error page must neither be
    // stored nor rewrite the record
    withContext(Dispatchers.IO) {
      Thread.sleep(500)
    }

    assertNull(coverStore.file("A::b2"))
    assertTrue(booksStore.data.first().single().cover.startsWith("http"))
    server.close()
  }

  @Test
  fun `backfill rewrites a remote record from an existing local cover`() = runTest {
    val file = assertNotNull(coverStore.write("A::b3", byteArrayOf(1, 2, 3)))
    booksStore.updateData {
      listOf(OnlineBook(source = "A", bookId = "b3", title = "T", cover = "https://example.com/cover.jpg"))
    }

    service.backfillCovers()

    // served from the existing file without touching the network
    assertEquals(file.toURI().toString(), booksStore.data.first().single().cover)
  }

  @Test
  fun `backfill falls back to the url when the cover file is gone`() = runTest {
    booksStore.updateData {
      listOf(
        OnlineBook(
          source = "A",
          bookId = "b5",
          title = "T",
          cover = "file:/data/user/0/app/files/online_covers/dead.img",
          coverUrl = "http://127.0.0.1:9/cover.jpg",
        ),
      )
    }

    service.backfillCovers()

    // the download cannot succeed here; the record must still stop pointing
    // at a file that does not exist
    assertEquals("http://127.0.0.1:9/cover.jpg", booksStore.data.first().single().cover)
  }

  @Test
  fun `restoring a cover path of another device falls back to its url`() = runTest {
    service.restoreShelfEntry(
      OnlineBook(
        source = "A",
        bookId = "b7",
        title = "T",
        cover = "file:/data/user/0/another.device/files/online_covers/dead.img",
        coverUrl = "https://example.com/cover.jpg",
      ),
    )

    val stored = booksStore.data.first().single()
    assertEquals("https://example.com/cover.jpg", stored.cover)
    assertEquals("https://example.com/cover.jpg", stored.coverUrl)
  }

  @Test
  fun `restoring keeps a cover file that is really on this device`() = runTest {
    val file = assertNotNull(coverStore.write("A::b8", byteArrayOf(9, 9)))

    service.restoreShelfEntry(
      OnlineBook(source = "A", bookId = "b8", title = "T", cover = file.toURI().toString()),
    )

    assertEquals(file.toURI().toString(), booksStore.data.first().single().cover)
  }

  @Test
  fun `extension sources work without any server configuration`() = runTest {
    val backend = FakeExtensionBackend()
    val service = OnlineSourceService(
      FakeStore(false),
      FakeStore(""),
      FakeStore(""),
      FakeStore(""),
      FakeBooksStore(),
      chapterStore,
      coverStore,
      OkHttpClient(),
      mockk(relaxed = false),
      dispatcherProvider,
      setOf(backend),
    )

    // isConfigured() must be true from the extension alone, and the server
    // client must never be touched
    assertEquals(true, service.isConfigured())
    assertEquals(
      listOf(OnlineSourceInfo(name = "jdr:demo", displayName = "演示源", enabled = true)),
      service.sources(),
    )
    assertEquals(
      listOf(OnlineSearchResult(source = "jdr:demo", bookId = "b1", title = "书")),
      service.search("jdr:demo", "斗罗"),
    )
    assertEquals(
      listOf(OnlineChapter(id = "c1", title = "第1集", order = 1)),
      service.chapters("jdr:demo", "b1"),
    )
    assertEquals("https://audio.example.com/a.mp3", service.resolveDirectUrl("jdr:demo", "b1", "c1")?.url)
    assertTrue(backend.searched)
  }

  private class FakeExtensionBackend : ExtensionOnlineSource {

    var searched = false

    override fun handles(source: String): Boolean = source.startsWith("jdr:")

    override suspend fun hasEnabledSources(): Boolean = true

    override suspend fun enabledSourceInfos(): List<OnlineSourceInfo> =
      listOf(OnlineSourceInfo(name = "jdr:demo", displayName = "演示源", enabled = true))

    override suspend fun search(
      source: String,
      keyword: String,
    ): List<OnlineSearchResult> {
      searched = true
      return listOf(OnlineSearchResult(source = source, bookId = "b1", title = "书"))
    }

    override suspend fun chapters(
      source: String,
      bookId: String,
    ): List<OnlineChapter> = listOf(OnlineChapter(id = "c1", title = "第1集", order = 1))

    override suspend fun resolveDirectUrl(
      source: String,
      bookId: String,
      chapterId: String,
    ): ResolvedStream = ResolvedStream("https://audio.example.com/a.mp3")
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

  private class FakeBooksStore : DataStore<List<OnlineBook>> {
    private val state = MutableStateFlow(emptyList<OnlineBook>())
    override val data: Flow<List<OnlineBook>> = state
    override suspend fun updateData(transform: suspend (List<OnlineBook>) -> List<OnlineBook>): List<OnlineBook> {
      val next = transform(state.value)
      state.value = next
      return next
    }
  }
}
