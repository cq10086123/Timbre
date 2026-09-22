package voice.core.playback

import kotlinx.serialization.json.Json
import voice.core.data.BookId
import voice.core.data.ChapterId
import voice.core.playback.session.MediaId
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerControllerTest {

  private val bookId = BookId("online://book/main/42")
  private val otherBookId = BookId("file:///audiobooks/other")

  private fun mediaIdOf(mediaId: MediaId): String = Json.encodeToString(MediaId.serializer(), mediaId)

  private fun chapterMarkOf(bookId: BookId): String = mediaIdOf(
    MediaId.ChapterMark(
      bookId = bookId,
      chapterId = ChapterId("online://play?s=main&b=42&c=1"),
      markIndex = 0,
      startMs = 0,
      endMs = 30_000,
    ),
  )

  @Test
  fun `assembled playlist with the target item is ready for the seek`() {
    assertTrue(
      playlistAssembled(
        currentMediaId = chapterMarkOf(bookId),
        mediaItemCount = 120,
        bookId = bookId,
        itemIndex = 119,
      ),
    )
  }

  @Test
  fun `placeholder book item is not an assembled playlist`() {
    // what maybePrepare leaves behind while VoicePlayer is still assembling
    assertFalse(
      playlistAssembled(
        currentMediaId = mediaIdOf(MediaId.Book(bookId)),
        mediaItemCount = 1,
        bookId = bookId,
        itemIndex = 0,
      ),
    )
  }

  @Test
  fun `empty playlist is not assembled`() {
    assertFalse(
      playlistAssembled(
        currentMediaId = null,
        mediaItemCount = 0,
        bookId = bookId,
        itemIndex = 0,
      ),
    )
  }

  @Test
  fun `item beyond the playlist is not assembled`() {
    assertFalse(
      playlistAssembled(
        currentMediaId = chapterMarkOf(bookId),
        mediaItemCount = 10,
        bookId = bookId,
        itemIndex = 10,
      ),
    )
  }

  @Test
  fun `playlist of another book is not assembled`() {
    assertFalse(
      playlistAssembled(
        currentMediaId = chapterMarkOf(otherBookId),
        mediaItemCount = 120,
        bookId = bookId,
        itemIndex = 5,
      ),
    )
  }

  @Test
  fun `unparsable media id is not assembled`() {
    assertFalse(
      playlistAssembled(
        currentMediaId = "not a media id",
        mediaItemCount = 120,
        bookId = bookId,
        itemIndex = 5,
      ),
    )
  }

  @Test
  fun `playlist of the book counts as loaded even beyond the item`() {
    // a partially imported book keeps appending items: the seek belongs to it
    assertTrue(playlistOfBookLoaded(currentMediaId = chapterMarkOf(bookId), bookId = bookId))
  }

  @Test
  fun `placeholder and other books do not count as loaded`() {
    assertFalse(playlistOfBookLoaded(currentMediaId = mediaIdOf(MediaId.Book(bookId)), bookId = bookId))
    assertFalse(playlistOfBookLoaded(currentMediaId = chapterMarkOf(otherBookId), bookId = bookId))
    assertFalse(playlistOfBookLoaded(currentMediaId = null, bookId = bookId))
    assertFalse(playlistOfBookLoaded(currentMediaId = "not a media id", bookId = bookId))
  }
}
