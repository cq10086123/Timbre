package voice.core.online

import androidx.datastore.core.DataStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import voice.core.data.BookId
import voice.core.data.ChapterId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OnlinePlaybackCatalogTest {

  private val service = mockk<OnlineSourceService>()
  private val catalog = OnlinePlaybackCatalog(service, FakeBooksStore())

  private fun shelfBook() = OnlineBook(
    source = SOURCE,
    bookId = BOOK_ID,
    title = "凡人修仙传",
    author = "忘语",
    chapters = listOf(
      OnlineChapter(id = "c1", title = "第1集 二愣子", durationSeconds = 1800),
      OnlineChapter(id = "c2", title = "第2集 青牛镇", durationSeconds = 1750),
      OnlineChapter(id = "c3", title = "第3集", durationSeconds = 0),
    ),
  )

  private fun bookId(): BookId = BookId(OnlineUri.buildBookUri(SOURCE, BOOK_ID))

  @Test
  fun `book uri round trip`() {
    val uri = OnlineUri.buildBookUri("main", "abc/42")
    assertEquals(OnlineBookRef("main", "abc/42"), OnlineUri.parseBookUri(uri))
  }

  @Test
  fun `non online uris do not parse as book`() {
    assertNull(OnlineUri.parseBookUri("https://example.com/a"))
    assertNull(OnlineUri.parseBookUri("online://play?s=main&b=42&c=1"))
    assertNull(OnlineUri.parseBookUri("online://book/main"))
  }

  @Test
  fun `isOnlineBookId only accepts online book uris`() {
    assertTrue(catalog.isOnlineBookId(bookId()))
    assertFalse(catalog.isOnlineBookId(BookId("content://media/1")))
  }

  @Test
  fun `synthesizes a book with online chapter uris`() = runTest {
    coEvery { service.shelfBook("main::$BOOK_ID") } returns shelfBook()

    val book = assertNotNull(catalog.book(bookId()))
    assertEquals("凡人修仙传", book.content.name)
    assertEquals("忘语", book.content.author)
    assertEquals(3, book.chapters.size)
    assertEquals(
      OnlineUri.build(SOURCE, BOOK_ID, "c1"),
      book.content.currentChapter.value,
    )
    assertEquals(1_800_000L, book.chapters[0].duration)
    // an unknown duration falls back to a generous default so the player
    // never believes the chapter is over after a second
    assertEquals(30 * 60_000L, book.chapters[2].duration)
    assertEquals(0L, book.content.positionInChapter)
  }

  @Test
  fun `starts at the requested chapter`() = runTest {
    coEvery { service.shelfBook("main::$BOOK_ID") } returns shelfBook()
    catalog.requestStartAt(SOURCE, BOOK_ID, "c2")

    val book = assertNotNull(catalog.book(bookId()))
    assertEquals(OnlineUri.build(SOURCE, BOOK_ID, "c2"), book.content.currentChapter.value)
    assertEquals(0L, book.content.positionInChapter)
  }

  @Test
  fun `returns null for non online book ids and unknown shelf entries`() = runTest {
    coEvery { service.shelfBook(any()) } returns null

    assertNull(catalog.book(BookId("content://media/1")))
    assertNull(catalog.book(bookId()))
    assertNull(catalog.content(bookId()))
  }

  @Test
  fun `episode number comes from the title with a positional fallback`() {
    assertEquals(1, OnlinePlaybackCatalog.episodeNumber("第1集 二愣子", 5))
    assertEquals(23, OnlinePlaybackCatalog.episodeNumber("第023集", 5))
    assertEquals(6, OnlinePlaybackCatalog.episodeNumber("没有数字的标题", 5))
  }

  @Test
  fun `file matching accepts padded and plain episode numbers`() {
    assertTrue(OnlinePlaybackCatalog.fileMatchesEpisode("第12集.mp3", 12))
    assertTrue(OnlinePlaybackCatalog.fileMatchesEpisode("0012.mp3", 12))
    assertFalse(OnlinePlaybackCatalog.fileMatchesEpisode("第112集.mp3", 12))
    assertFalse(OnlinePlaybackCatalog.fileMatchesEpisode("封面.jpg", 12))
  }

  @Test
  fun `title similarity is a loose containment match`() {
    assertTrue(OnlinePlaybackCatalog.titleSimilar("凡人修仙传", "凡人修仙传"))
    assertTrue(OnlinePlaybackCatalog.titleSimilar("凡人修仙传（精校版）", "凡人修仙传"))
    assertFalse(OnlinePlaybackCatalog.titleSimilar("仙逆", "凡人修仙传"))
    assertFalse(OnlinePlaybackCatalog.titleSimilar("", "凡人修仙传"))
  }

  @Test
  fun `search stashed book plays without a shelf entry`() = runTest {
    coEvery { service.shelfBook(any()) } returns null
    catalog.stashForPlayback(shelfBook().copy(cover = "https://example.com/cover.jpg"))

    // both the player and the player screen assemble the book: the stash
    // must survive the first assembly
    assertNotNull(catalog.book(bookId()))
    val book = assertNotNull(catalog.book(bookId()))
    assertEquals(3, book.chapters.size)
    assertEquals("https://example.com/cover.jpg", catalog.onlineCover(bookId()))
  }

  @Test
  fun `shelf book wins over a stale search stash`() = runTest {
    coEvery { service.shelfBook(any()) } returns shelfBook().copy(title = "书架上的新版")
    catalog.stashForPlayback(shelfBook().copy(title = "搜索时的旧版"))

    assertEquals("书架上的新版", assertNotNull(catalog.book(bookId())).content.name)
  }

  @Test
  fun `measured durations are keyed by source`() = runTest {
    coEvery { service.shelfBook(any()) } returns null
    val thirdPartyId = BookId(OnlineUri.buildBookUri("A", BOOK_ID))
    catalog.stashForPlayback(shelfBook().copy(source = "A", cover = "https://example.com/a.jpg"))

    // a measurement for the main catalog must not leak into source A books
    catalog.recordMeasuredDuration("main", BOOK_ID, "c3", 120_000L)
    assertEquals(30 * 60_000L, assertNotNull(catalog.book(thirdPartyId)).chapters[2].duration)

    catalog.recordMeasuredDuration("A", BOOK_ID, "c3", 90_000L)
    catalog.stashForPlayback(shelfBook().copy(source = "A"))
    assertEquals(90_000L, assertNotNull(catalog.book(thirdPartyId)).chapters[2].duration)
  }

  @Test
  fun `resolved stream urls are reused until they are invalidated`() = runTest {
    // the player re-opens the stream on every seek: asking the source again
    // would stall the chapter the user is already listening to
    val ref = OnlineChapterRef(THIRD_PARTY_SOURCE, BOOK_ID, "c1")
    coEvery { service.resolveDirectUrl(THIRD_PARTY_SOURCE, BOOK_ID, "c1") } returns STREAM_URL

    assertEquals(STREAM_URL, catalog.resolveStreamUrl(ref))
    assertEquals(STREAM_URL, catalog.resolveStreamUrl(ref))
    coVerify(exactly = 1) { val _ = service.resolveDirectUrl(THIRD_PARTY_SOURCE, BOOK_ID, "c1") }

    // a signed link the server rejects is dropped, so the next resolve is fresh
    assertTrue(catalog.invalidateStreamUrl(ref))
    assertEquals(STREAM_URL, catalog.resolveStreamUrl(ref))
    coVerify(exactly = 2) {
      val _ = service.resolveDirectUrl(THIRD_PARTY_SOURCE, BOOK_ID, "c1")
    }
    assertFalse(catalog.invalidateStreamUrl(OnlineChapterRef(THIRD_PARTY_SOURCE, BOOK_ID, "unknown")))
  }

  @Test
  fun `content skips building chapter rows`() = runTest {
    coEvery { service.shelfBook("main::$BOOK_ID") } returns shelfBook()
    val content = assertNotNull(catalog.content(bookId()))
    assertEquals("凡人修仙传", content.name)
    assertEquals(3, content.chapters.size)
    assertEquals(OnlineUri.build(SOURCE, BOOK_ID, "c1"), content.currentChapter.value)
  }

  @Test
  fun `concurrent resolves of the same chapter share one source call`() = runTest {
    val ref = OnlineChapterRef(THIRD_PARTY_SOURCE, BOOK_ID, "c1")
    val started = kotlinx.coroutines.CompletableDeferred<Unit>()
    val release = kotlinx.coroutines.CompletableDeferred<Unit>()
    var calls = 0
    coEvery { service.resolveDirectUrl(THIRD_PARTY_SOURCE, BOOK_ID, "c1") } coAnswers {
      calls++
      started.complete(Unit)
      release.await()
      STREAM_URL
    }

    val first = async(Dispatchers.Default) { catalog.resolveStreamUrl(ref) }
    started.await()
    val second = async(Dispatchers.Default) { catalog.resolveStreamUrl(ref) }
    // give the second caller a moment to attach to the in-flight deferred
    yield()
    release.complete(Unit)

    assertEquals(STREAM_URL, first.await())
    assertEquals(STREAM_URL, second.await())
    assertEquals(1, calls)
  }

  @Test
  fun `invalidate prevents a racing resolve from re-caching a rejected url`() = runTest {
    val ref = OnlineChapterRef(THIRD_PARTY_SOURCE, BOOK_ID, "c1")
    val started = kotlinx.coroutines.CompletableDeferred<Unit>()
    val release = kotlinx.coroutines.CompletableDeferred<Unit>()
    coEvery { service.resolveDirectUrl(THIRD_PARTY_SOURCE, BOOK_ID, "c1") } coAnswers {
      started.complete(Unit)
      release.await()
      "https://cdn.example.com/rejected.mp3"
    }

    val slow = async(Dispatchers.Default) { catalog.resolveStreamUrl(ref) }
    started.await()
    // data source rejected the url while resolve is still finishing
    assertFalse(catalog.invalidateStreamUrl(ref))
    release.complete(Unit)
    assertEquals("https://cdn.example.com/rejected.mp3", slow.await())

    // next resolve must hit the source again instead of serving the rejected url
    coEvery { service.resolveDirectUrl(THIRD_PARTY_SOURCE, BOOK_ID, "c1") } returns STREAM_URL
    assertEquals(STREAM_URL, catalog.resolveStreamUrl(ref))
    coVerify(atLeast = 2) {
      val _ = service.resolveDirectUrl(THIRD_PARTY_SOURCE, BOOK_ID, "c1")
    }
  }

  @Test
  fun `the resolving state is reported per book`() = runTest {
    val ref = OnlineChapterRef(THIRD_PARTY_SOURCE, BOOK_ID, "c1")
    var resolvingWhileRunning: Set<String>? = null
    coEvery { service.resolveDirectUrl(THIRD_PARTY_SOURCE, BOOK_ID, "c1") } coAnswers {
      resolvingWhileRunning = catalog.resolvingBooks.value
      STREAM_URL
    }

    assertTrue(catalog.resolvingBooks.value.isEmpty())
    assertEquals(STREAM_URL, catalog.resolveStreamUrl(ref))

    assertEquals(
      expected = setOf(OnlineUri.buildBookUri(THIRD_PARTY_SOURCE, BOOK_ID)),
      actual = resolvingWhileRunning,
    )
    assertTrue(catalog.resolvingBooks.value.isEmpty())
  }

  @Test
  fun `a failed resolve clears the resolving state again`() = runTest {
    coEvery { service.resolveDirectUrl(THIRD_PARTY_SOURCE, BOOK_ID, "c1") } returns null

    assertNull(catalog.resolveStreamUrl(OnlineChapterRef(THIRD_PARTY_SOURCE, BOOK_ID, "c1")))
    assertTrue(catalog.resolvingBooks.value.isEmpty())
  }

  @Test
  fun `a persisted shelf position resumes the book`() = runTest {
    coEvery { service.shelfBook("main::$BOOK_ID") } returns
      shelfBook().copy(currentChapterId = "c2", positionMs = 42_000L)
    val catalog = OnlinePlaybackCatalog(service, FakeBooksStore())

    val book = assertNotNull(catalog.book(bookId()))
    assertEquals(OnlineUri.build(SOURCE, BOOK_ID, "c2"), book.content.currentChapter.value)
    assertEquals(42_000L, book.content.positionInChapter)
  }

  @Test
  fun `a session position wins over the persisted one`() = runTest {
    coEvery { service.shelfBook("main::$BOOK_ID") } returns
      shelfBook().copy(currentChapterId = "c2", positionMs = 42_000L)
    val catalog = OnlinePlaybackCatalog(service, FakeBooksStore())

    catalog.updatePosition(
      bookId = bookId(),
      chapterId = ChapterId(OnlineUri.build(SOURCE, BOOK_ID, "c3")),
      positionMs = 5_000L,
    )

    val book = assertNotNull(catalog.book(bookId()))
    assertEquals(OnlineUri.build(SOURCE, BOOK_ID, "c3"), book.content.currentChapter.value)
    assertEquals(5_000L, book.content.positionInChapter)
  }

  @Test
  fun `updatePosition persists to the shelf entry`() = runTest {
    val store = FakeBooksStore(listOf(shelfBook()))
    val catalog = OnlinePlaybackCatalog(service, store)

    catalog.updatePosition(
      bookId = bookId(),
      chapterId = ChapterId(OnlineUri.build(SOURCE, BOOK_ID, "c2")),
      positionMs = 42_000L,
    )

    // the write happens on the io dispatcher outside the test scheduler
    val deadline = System.currentTimeMillis() + 5_000
    while (store.data.first().firstOrNull()?.currentChapterId != "c2") {
      check(System.currentTimeMillis() < deadline) { "the position was never persisted" }
      Thread.sleep(10)
    }
    val stored = store.data.first().single()
    assertEquals("c2", stored.currentChapterId)
    assertEquals(42_000L, stored.positionMs)
  }

  @Test
  fun `a chapter change persists immediately without waiting for the interval`() = runTest {
    val store = FakeBooksStore(listOf(shelfBook()))
    val catalog = OnlinePlaybackCatalog(service, store)

    catalog.updatePosition(
      bookId = bookId(),
      chapterId = ChapterId(OnlineUri.build(SOURCE, BOOK_ID, "c2")),
      positionMs = 1_000L,
    )
    awaitStoreChapter(store, "c2")

    catalog.updatePosition(
      bookId = bookId(),
      chapterId = ChapterId(OnlineUri.build(SOURCE, BOOK_ID, "c3")),
      positionMs = 2_000L,
    )
    awaitStoreChapter(store, "c3")
  }

  @Test
  fun `localBook shows the persisted progress`() {
    val book = catalog.localBook(
      shelfBook().copy(currentChapterId = "c2", positionMs = 42_000L),
    )
    assertEquals(OnlineUri.build(SOURCE, BOOK_ID, "c2"), book.content.currentChapter.value)
    assertEquals(42_000L, book.content.positionInChapter)
  }

  /** Waits for the io dispatcher to have persisted [chapterId]. */
  private suspend fun awaitStoreChapter(
    store: FakeBooksStore,
    chapterId: String,
  ) = withContext(Dispatchers.IO) {
    val deadline = System.currentTimeMillis() + 5_000
    while (store.data.first().firstOrNull()?.currentChapterId != chapterId) {
      check(System.currentTimeMillis() < deadline) { "the position was never persisted to $chapterId" }
      Thread.sleep(10)
    }
  }

  private companion object {
    const val SOURCE = "main"
    const val BOOK_ID = "42"
    const val THIRD_PARTY_SOURCE = "A"
    const val STREAM_URL = "https://cdn.example.com/c1.mp3"
  }
}

/** In-memory books store; updateData runs the transform synchronously. */
private class FakeBooksStore(initial: List<OnlineBook> = emptyList()) : DataStore<List<OnlineBook>> {
  private val state = MutableStateFlow(initial)

  override val data: Flow<List<OnlineBook>> = state

  override suspend fun updateData(transform: suspend (List<OnlineBook>) -> List<OnlineBook>): List<OnlineBook> {
    val next = transform(state.value)
    state.value = next
    return next
  }
}

class OnlineStreamDurationProbeTest {

  @Test
  fun `estimates cbr duration from size and bitrate`() {
    // mpeg1 layer III, 128 kbps, 44100 Hz: FF FB 90 00. The 128 byte id3v1
    // trailer is excluded from the audio bytes.
    val plain = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x00, 0, 0, 0, 0)
    assertEquals(62_492L, OnlineStreamDurationProbe.estimateDurationMs(1_000_000L, plain))
  }

  @Test
  fun `uses xing frame count when present`() {
    val head = mutableListOf<Byte>()
    head += listOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x00)
    head += "Xing".map { it.code.toByte() }
    head += listOf(0x00, 0x00, 0x00, 0x03) // flags: frames + bytes
    head += listOf(0x00, 0x00, 0x03, 0xE8.toByte()) // 1000 frames
    head += listOf(0, 0, 0, 0)
    val duration = OnlineStreamDurationProbe.estimateDurationMs(1_000_000L, head.toByteArray())
    // 1000 frames * 1152 samples * 1000 / 44100 Hz
    assertEquals(26_122L, duration)
  }

  @Test
  fun `returns null for garbage`() {
    val head = ByteArray(64) { it.toByte() }
    head[0] = 0x12
    assertNull(OnlineStreamDurationProbe.estimateDurationMs(1_000_000L, head))
  }

  @Test
  fun `uses vbri frame count when present`() {
    // mpeg1 stereo frame, then a VBRI tag at frame + 36 with 1000 frames at +14
    val head = ByteArray(64)
    head[0] = 0xFF.toByte()
    head[1] = 0xFB.toByte()
    head[2] = 0x90.toByte()
    "VBRI".forEachIndexed { i, c -> head[36 + i] = c.code.toByte() }
    head[50] = 0x00
    head[51] = 0x00
    head[52] = 0x03.toByte()
    head[53] = 0xE8.toByte()
    // 1000 frames * 1152 samples * 1000 / 44100 Hz
    assertEquals(26_122L, OnlineStreamDurationProbe.estimateDurationMs(1_000_000L, head))
  }

  @Test
  fun `exact xing offset wins over a misplaced tag`() {
    // mpeg1 stereo frame: the real Xing header lives at frame + 36. A tag
    // without a frame count earlier in the window must not shadow it.
    val head = ByteArray(64)
    head[0] = 0xFF.toByte()
    head[1] = 0xFB.toByte()
    head[2] = 0x90.toByte()
    "Xing".forEachIndexed { i, c -> head[10 + i] = c.code.toByte() }
    "Xing".forEachIndexed { i, c -> head[36 + i] = c.code.toByte() }
    head[40] = 0x00
    head[41] = 0x00
    head[42] = 0x00
    head[43] = 0x03
    head[44] = 0x00
    head[45] = 0x00
    head[46] = 0x03.toByte()
    head[47] = 0xE8.toByte()
    assertEquals(26_122L, OnlineStreamDurationProbe.estimateDurationMs(1_000_000L, head))
  }

  @Test
  fun `cbr estimate excludes the id3v2 tag`() {
    // id3v2 with a 100 byte body, then a 128 kbps mpeg1 frame
    val head = ByteArray(110 + 8)
    head[0] = 'I'.code.toByte()
    head[1] = 'D'.code.toByte()
    head[2] = '3'.code.toByte()
    head[9] = 100.toByte()
    head[110] = 0xFF.toByte()
    head[111] = 0xFB.toByte()
    head[112] = 0x90.toByte()
    // (1_000_000 - 110 - 128) * 8 / 128
    assertEquals(62_485L, OnlineStreamDurationProbe.estimateDurationMs(1_000_000L, head))
  }
}
