package voice.core.scanner.mp4

import voice.core.data.MarkData

internal data class Mp4ChpaterExtractorOutput(
  val chunkOffsets: MutableList<List<Long>> = mutableListOf(),
  val durations: MutableList<List<SttsEntry>> = mutableListOf(),
  val stscEntries: MutableList<List<StscEntry>> = mutableListOf(),
  val timeScales: MutableList<Long> = mutableListOf(),
  var chplChapters: List<MarkData> = emptyList(),
  var chapterTrackId: Int? = null,
  /** The handler type of every track, in the order the tracks appear in the file. */
  val trackHandlers: MutableList<String> = mutableListOf(),
  /** The duration of every track in ms, in the order the tracks appear in the file. */
  val trackDurationsMs: MutableList<Long> = mutableListOf(),
  var title: String? = null,
  var artist: String? = null,
  var album: String? = null,
  var genre: String? = null,
  var narrator: String? = null,
  /** True when the box that holds the tags was seen. */
  var sawTags: Boolean = false,
  /**
   * Set when the tags are stored in a form the box parse doesn't resolve, so
   * the metadata retriever has to read them.
   */
  var tagsNeedRetriever: Boolean = false,
) {

  /**
   * The duration of the audio track.
   *
   * The movie header is not a reliable source for it: it covers the whole movie
   * and can be considerably longer than the audio, for example when the file
   * also contains a cover art track.
   */
  val audioDurationMs: Long?
    get() {
      // both lists are filled once per track, so they only line up when every
      // track had both of its boxes
      if (trackHandlers.size != trackDurationsMs.size) return null
      val audioTrackIndex = trackHandlers.indexOf(AUDIO_HANDLER_TYPE)
      if (audioTrackIndex == -1) return null
      return trackDurationsMs.getOrNull(audioTrackIndex)
        ?.takeIf { it > 0 }
    }
}

private const val AUDIO_HANDLER_TYPE = "soun"

internal data class SttsEntry(
  val sampleCount: Long,
  val sampleDuration: Long,
)

internal data class StscEntry(
  val firstChunk: Long,
  val samplesPerChunk: Int,
)

/**
 * Everything the box parse of a single file yielded.
 *
 * @param durationMs the duration of the audio track, or null when the boxes
 * didn't contain one
 * @param tagsAreComplete false when the tags have to be read by the metadata
 * retriever because they are stored in a form the box parse doesn't resolve
 */
internal data class Mp4FileMetadata(
  val chapters: List<MarkData>,
  val durationMs: Long?,
  val title: String?,
  val artist: String?,
  val album: String?,
  val genre: String?,
  val narrator: String?,
  val tagsAreComplete: Boolean,
)
