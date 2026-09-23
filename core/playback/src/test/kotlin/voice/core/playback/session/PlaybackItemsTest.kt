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

  @Test
  fun `playback item for position does not need the full list`() {
    val chapters = (0 until 8).map { i ->
      chapter(duration = 10_000, MarkData(startMs = 0, name = "C$i"))
    }
    val book = book(chapters)
    val target = chapters[5]
    val item = book.playbackItemForPosition(target.id, 1_000)
    assertEquals(expected = 5, actual = item?.index)
    assertEquals(expected = target.id, actual = item?.chapter?.id)
    assertEquals(expected = 1_000, actual = item?.positionInMediaItem(1_000))
  }

  @Test
  fun `leading prefix through resume keeps global item indexes from zero`() {
    // mirrors MediaItemProvider.playbackItemsPrefix: [0, resume+ahead)
    val chapters = (0 until 100).map { i ->
      chapter(duration = 10_000, MarkData(startMs = 0, name = "C$i"))
    }
    val book = book(chapters)
    val resume = 50
    val ahead = 16
    val until = (resume + 1 + ahead).coerceAtMost(100)
    val prefix = book.playbackItems(fromItemIndex = 0, untilItemIndex = until)
    assertEquals(expected = until, actual = prefix.size)
    assertEquals(expected = 0, actual = prefix.first().index)
    assertEquals(expected = resume, actual = prefix[resume].index)
    assertEquals(expected = until - 1, actual = prefix.last().index)
  }

  @Test
  fun `ranged playback items stop at until index`() {
    val chapters = (0 until 20).map { i ->
      chapter(duration = 10_000, MarkData(startMs = 0, name = "C$i"))
    }
    val book = book(chapters)
    val range = book.playbackItems(fromItemIndex = 15, untilItemIndex = 18)
    assertEquals(expected = listOf(15, 16, 17), actual = range.map { it.index })
  }

  @Test
  fun `tail after prefix starts at the next global index`() {
    val chapters = (0 until 20).map { i ->
      chapter(duration = 10_000, MarkData(startMs = 0, name = "C$i"))
    }
    val book = book(chapters)
    val prefixUntil = 12
    val tail = book.playbackItems(fromItemIndex = prefixUntil)
    assertEquals(expected = 12, actual = tail.first().index)
    assertEquals(expected = 19, actual = tail.last().index)
    assertEquals(
      expected = book.playbackItems().map { it.index },
      actual = (book.playbackItems(0, prefixUntil) + tail).map { it.index },
    )
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
