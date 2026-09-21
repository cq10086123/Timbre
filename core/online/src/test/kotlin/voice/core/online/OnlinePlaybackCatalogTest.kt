package voice.core.online

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import voice.core.data.BookId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OnlinePlaybackCatalogTest {

  private val service = mockk<OnlineSourceService>()
  private val catalog = OnlinePlaybackCatalog(service)

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
    // an unknown duration must never clip playback away
    assertEquals(1_000L, book.chapters[2].duration)
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

  private companion object {
    const val SOURCE = "main"
    const val BOOK_ID = "42"
  }
}
