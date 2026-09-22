package voice.core.playback.playstate

/**
 * The duration of the stream the player reports, or 0 when it must not be kept
 * as a measurement of the content.
 *
 * Every playback item is clipped to the mark it plays, and an online chapter
 * whose length the source does not report is assembled with a placeholder. As
 * soon as the real audio is longer than that clip, the player reports the end
 * of the clip: recording it would overwrite the length measured from the
 * stream (or the one the source reported) with the placeholder, and every
 * later assembly of the book would clip the chapter again.
 *
 * @param reportedDurationMs the duration the player reports for the current item
 * @param declaredDurationMs the duration the current item was built with, or null
 */
internal fun measuredStreamDurationMs(
  reportedDurationMs: Long,
  declaredDurationMs: Long?,
): Long {
  if (reportedDurationMs <= 0L) return 0L
  // an item without a declared duration reports the length of the stream
  // (media3 uses C.TIME_UNSET when the metadata carries no duration at all)
  if (declaredDurationMs == null || declaredDurationMs <= 0L) return reportedDurationMs
  return if (reportedDurationMs >= declaredDurationMs) 0L else reportedDurationMs
}
