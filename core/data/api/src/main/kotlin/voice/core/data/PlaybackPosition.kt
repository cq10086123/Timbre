package voice.core.data

/**
 * The playback position of one book, published without touching the book list
 * the shelf and the player observe.
 *
 * Positions arrive several times per second while playing. Rebuilding the
 * [BookContent] (and with it the whole [Book]) for every update re-emitted all
 * book flows and made the shelf re-group and the player screen re-assemble the
 * running book at that rate; this lightweight value is what those observers
 * read instead. Only a persisted update (every few seconds, on a chapter
 * change or on pause) still rewrites the content row.
 */
public data class PlaybackPosition(
  val chapterId: ChapterId,
  val positionInChapter: Long,
)
