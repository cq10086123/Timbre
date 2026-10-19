package voice.core.data

public data class Book(
  val content: BookContent,
  val chapters: List<Chapter>,
) {

  val id: BookId = content.id

  init {
    check(chapters.size == content.chapters.size) {
      "Different chapter count in $this"
    }
    // an index based comparison instead of `chapters.map { it.id } == ...`:
    // a playing book is rebuilt on every persisted position (and the shelf
    // overlays the live one per tick), so the check must not allocate a list
    check(chapters.indices.all { chapters[it].id == content.chapters[it] }) {
      "Different chapter order in $this"
    }
  }

  // the derived values are only computed when they are read: a rebuild for a
  // playback position does not need the total duration, and summing the
  // chapters eagerly made every such rebuild O(chapters)
  val currentChapter: Chapter by lazy { chapters[content.currentChapterIndex] }
  val currentMark: ChapterMark by lazy { currentChapter.markForPosition(content.positionInChapter) }

  val position: Long by lazy {
    var positionBeforeChapter = 0L
    for (chapter in chapters) {
      if (chapter.id == content.currentChapter) break
      positionBeforeChapter += chapter.duration
    }
    positionBeforeChapter + content.positionInChapter
  }
  val duration: Long by lazy { chapters.sumOf { it.duration } }

  public inline fun update(update: (BookContent) -> BookContent): Book {
    return copy(content = update(content))
  }

  public fun withElapsedPosition(
    elapsedTime: Long,
    playbackSpeed: Float,
  ): Book {
    val addedAudioTime = (elapsedTime * playbackSpeed).toLong()
    var remainingPositionInChapter = content.positionInChapter + addedAudioTime
    for (index in content.currentChapterIndex until chapters.size) {
      val chapter = chapters[index]
      if (remainingPositionInChapter < chapter.duration || index == chapters.lastIndex) {
        return update {
          it.copy(
            currentChapter = chapter.id,
            positionInChapter = remainingPositionInChapter.coerceAtMost(chapter.duration),
          )
        }
      }
      remainingPositionInChapter -= chapter.duration
    }

    return this
  }
}
