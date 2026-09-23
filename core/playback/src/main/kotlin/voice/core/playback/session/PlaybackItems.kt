package voice.core.playback.session

import voice.core.data.Book
import voice.core.data.BookId
import voice.core.data.Chapter
import voice.core.data.ChapterId
import voice.core.data.ChapterMark
import voice.core.data.durationMs
import voice.core.data.markForPosition

internal data class PlaybackItem(
  val index: Int,
  val bookId: BookId,
  val chapter: Chapter,
  val markIndex: Int,
  val mark: ChapterMark,
) {
  val mediaId: MediaId.ChapterMark
    get() = MediaId.ChapterMark(
      bookId = bookId,
      chapterId = chapter.id,
      markIndex = markIndex,
      startMs = mark.startMs,
      endMs = mark.endMs,
    )
}

internal fun Book.playbackItems(): List<PlaybackItem> {
  var index = 0
  return chapters.flatMap { chapter ->
    chapter.chapterMarks.mapIndexed { markIndex, mark ->
      PlaybackItem(
        index = index++,
        bookId = id,
        chapter = chapter,
        markIndex = markIndex,
        mark = mark,
      )
    }
  }
}

/**
 * The playback items of a book in the half-open global index range
 * [[fromItemIndex], [untilItemIndex]).
 *
 * While a book is imported, its playlist only grows at the end. Building only
 * the appended tail keeps synchronizing a book with thousands of chapters
 * cheap instead of rebuilding every item for every imported batch. A resume
 * window around the current chapter uses both bounds so a late resume does
 * not allocate every trailing chapter.
 */
internal fun Book.playbackItems(
  fromItemIndex: Int,
  untilItemIndex: Int = Int.MAX_VALUE,
): List<PlaybackItem> {
  if (fromItemIndex <= 0 && untilItemIndex == Int.MAX_VALUE) {
    return playbackItems()
  }
  if (untilItemIndex <= fromItemIndex) {
    return emptyList()
  }
  val items = mutableListOf<PlaybackItem>()
  var index = 0
  chapters.forEach { chapter ->
    if (index >= untilItemIndex) return items
    val marks = chapter.chapterMarks
    if (index + marks.size > fromItemIndex) {
      val startMark = (fromItemIndex - index).coerceAtLeast(0)
      val endMark = (untilItemIndex - index).coerceAtMost(marks.size)
      for (markIndex in startMark until endMark) {
        items += PlaybackItem(
          index = index + markIndex,
          bookId = id,
          chapter = chapter,
          markIndex = markIndex,
          mark = marks[markIndex],
        )
      }
    }
    index += marks.size
  }
  return items
}

internal fun Book.playbackItemForPosition(
  chapterId: ChapterId,
  positionInChapterMs: Long,
): PlaybackItem? {
  // O(chapters) without allocating every PlaybackItem of a long book — setBook
  // used to build the full list twice (once here, once for media items).
  val chapter = chapters.firstOrNull { it.id == chapterId } ?: return null
  val mark = chapter.markForPosition(positionInChapterMs)
  val markIndex = chapter.chapterMarks.indexOf(mark)
  if (markIndex < 0) return null
  var index = markIndex
  for (previous in chapters) {
    if (previous.id == chapterId) break
    index += previous.chapterMarks.size
  }
  return PlaybackItem(
    index = index,
    bookId = id,
    chapter = chapter,
    markIndex = markIndex,
    mark = mark,
  )
}

/**
 * The position of a chapter in the playlist of [Book.playbackItems].
 *
 * Seeking happens often and shouldn't build an item for every chapter of the
 * book just to find the index of one of them.
 */
internal data class PlaybackPosition(
  val index: Int,
  val positionInMediaItemMs: Long,
)

internal fun Book.playbackPositionFor(
  chapterId: ChapterId,
  positionInChapterMs: Long,
): PlaybackPosition? {
  val chapter = chapters.firstOrNull { it.id == chapterId } ?: return null
  val mark = chapter.markForPosition(positionInChapterMs)
  val markIndex = chapter.chapterMarks.indexOf(mark)
  if (markIndex == -1) return null
  var index = markIndex
  for (previousChapter in chapters) {
    if (previousChapter.id == chapterId) break
    index += previousChapter.chapterMarks.size
  }
  return PlaybackPosition(
    index = index,
    positionInMediaItemMs = (positionInChapterMs - mark.startMs).coerceIn(0L, mark.durationMs),
  )
}

internal val MediaId.bookId: BookId?
  get() = when (this) {
    is MediaId.Book -> id
    is MediaId.Chapter -> bookId
    is MediaId.ChapterMark -> bookId
    MediaId.Recent,
    MediaId.Root,
    -> null
  }

internal val MediaId.realChapterId: ChapterId?
  get() = when (this) {
    is MediaId.Chapter -> chapterId
    is MediaId.ChapterMark -> chapterId
    is MediaId.Book,
    MediaId.Recent,
    MediaId.Root,
    -> null
  }

internal fun MediaId.positionInChapter(positionInCurrentMediaItemMs: Long): Long? {
  return when (this) {
    is MediaId.Chapter -> positionInCurrentMediaItemMs
    is MediaId.ChapterMark -> startMs + positionInCurrentMediaItemMs
    is MediaId.Book,
    MediaId.Recent,
    MediaId.Root,
    -> null
  }
}

internal fun PlaybackItem.positionInMediaItem(positionInChapterMs: Long): Long {
  return (positionInChapterMs - mark.startMs).coerceIn(0L, mark.durationMs)
}
