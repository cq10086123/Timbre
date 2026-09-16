package voice.core.playback.session

import voice.core.data.Chapter
import voice.core.data.ChapterId
import voice.core.data.MarkData
import voice.core.playback.session.search.book
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

class PlaybackItemsTest {

  @Test
  fun `maps file chapter position to clipped playback item`() {
    val chapter = chapter(
      duration = 20_000,
      MarkData(startMs = 0, name = "Intro"),
      MarkData(startMs = 12_000, name = "Chapter 1"),
    )
    val book = book(listOf(chapter))

    val playbackItem = book.playbackItemForPosition(
      chapterId = chapter.id,
      positionInChapterMs = 15_000,
    )

    assertEquals(expected = 1, actual = playbackItem?.index)
    assertEquals(expected = 3_000, actual = playbackItem?.positionInMediaItem(15_000))
    assertEquals(expected = 15_000, actual = playbackItem?.mediaId?.positionInChapter(3_000))
  }

  @Test
  fun `maps chapter duration position to last playback item`() {
    val chapter = chapter(
      duration = 20_000,
      MarkData(startMs = 0, name = "Intro"),
      MarkData(startMs = 12_000, name = "Chapter 1"),
    )
    val book = book(listOf(chapter))

    val playbackItem = book.playbackItemForPosition(
      chapterId = chapter.id,
      positionInChapterMs = chapter.duration,
    )

    assertEquals(expected = 1, actual = playbackItem?.index)
    assertEquals(expected = 7_999, actual = playbackItem?.positionInMediaItem(chapter.duration))
  }

  @Test
  fun `indexes marks across file chapters`() {
    val firstChapter = chapter(
      duration = 20_000,
      MarkData(startMs = 0, name = "One"),
      MarkData(startMs = 5_000, name = "Two"),
    )
    val secondChapter = chapter(
      duration = 20_000,
      MarkData(startMs = 0, name = "Three"),
      MarkData(startMs = 7_000, name = "Four"),
    )
    val book = book(listOf(firstChapter, secondChapter))

    val playbackItem = book.playbackItemForPosition(
      chapterId = secondChapter.id,
      positionInChapterMs = 8_000,
    )

    assertEquals(expected = 3, actual = playbackItem?.index)
    assertEquals(expected = secondChapter.id, actual = playbackItem?.mediaId?.realChapterId)
    assertEquals(expected = 1_000, actual = playbackItem?.positionInMediaItem(8_000))
  }

  @Test
  fun `playback items from an index keep the global indexes`() {
    val firstChapter = chapter(
      duration = 20_000,
      MarkData(startMs = 0, name = "One"),
      MarkData(startMs = 5_000, name = "Two"),
    )
    val secondChapter = chapter(
      duration = 20_000,
      MarkData(startMs = 0, name = "Three"),
      MarkData(startMs = 7_000, name = "Four"),
    )
    val thirdChapter = chapter(duration = 20_000, MarkData(startMs = 0, name = "Five"))
    val book = book(listOf(firstChapter, secondChapter, thirdChapter))

    val tail = book.playbackItems(fromItemIndex = 1)

    // the indexes must line up with the ones of the full item list because the
    // playlist positions are derived from them
    assertEquals(
      expected = book.playbackItems().drop(1),
      actual = tail,
    )
    assertEquals(expected = listOf(1, 2, 3, 4), actual = tail.map { it.index })
  }

  @Test
  fun `playback items from an index after the last item are empty`() {
    val chapter = chapter(duration = 20_000, MarkData(startMs = 0, name = "One"))
    val book = book(listOf(chapter))

    assertEquals(expected = emptyList(), actual = book.playbackItems(fromItemIndex = 1))
  }

  private fun chapter(
    @Suppress("SameParameterValue") duration: Long,
    vararg marks: MarkData,
  ): Chapter {
    return Chapter(
      id = ChapterId(Uuid.random().toString()),
      name = "chapter",
      duration = duration,
      fileLastModified = Instant.EPOCH,
      markData = marks.toList(),
      fileSize = 0,
    )
  }
}
