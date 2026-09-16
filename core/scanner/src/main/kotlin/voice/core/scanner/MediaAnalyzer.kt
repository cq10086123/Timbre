package voice.core.scanner

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.FileTypes
import androidx.media3.common.MediaItem
import androidx.media3.container.MdtaMetadataEntry
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.amr.AmrExtractor
import androidx.media3.extractor.metadata.id3.ChapterFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.extractor.ts.AdtsExtractor
import androidx.media3.inspector.MetadataRetriever
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.guava.await
import voice.core.data.MarkData
import voice.core.documentfile.CachedDocumentFile
import voice.core.documentfile.nameWithoutExtension
import voice.core.logging.api.Logger
import voice.core.scanner.matroska.MatroskaMetaDataExtractor
import voice.core.scanner.matroska.MatroskaParseException
import voice.core.scanner.mp4.Mp4ChapterExtractor
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds

@Inject
internal class MediaAnalyzer(
  private val context: Context,
  private val mp4ChapterExtractor: Mp4ChapterExtractor,
  private val matroskaExtractorFactory: MatroskaMetaDataExtractor.Factory,
) {

  // we use a custom MediaSourceFactory because the default one for the
  // retriever also extracts the covers. The extractor flags are the ones the
  // retriever would use on its own: reading the mp4 sample table is expensive
  // and not needed to get the duration, the tags and the chapter marks.
  private val mediaSourceFactory = DefaultMediaSourceFactory(
    context,
    DefaultExtractorsFactory()
      .setConstantBitrateSeekingEnabled(true)
      .setMp4ExtractorFlags(Mp4Extractor.FLAG_READ_SEF_DATA or Mp4Extractor.FLAG_OMIT_TRACK_SAMPLE_TABLE)
      .setAdtsExtractorFlags(AdtsExtractor.FLAG_ENABLE_CONSTANT_BITRATE_SEEKING)
      .setAmrExtractorFlags(AmrExtractor.FLAG_ENABLE_CONSTANT_BITRATE_SEEKING),
  )

  suspend fun analyze(file: CachedDocumentFile): Metadata? {
    val builder = Metadata.Builder(file.nameWithoutExtension())
    val fileType = FileTypes.inferFileTypeFromUri(file.uri)
    val extension = (file.name ?: "").substringAfterLast(delimiter = ".", missingDelimiterValue = "").lowercase()
    val isMp4 = fileType == FileTypes.MP4 || extension == "mp4" || extension == "m4a" || extension == "m4b"

    // the boxes of an mp4 hold the duration, the tags and the chapters, so the
    // file does not have to be prepared by exoplayer as well
    val mp4 = if (isMp4) mp4ChapterExtractor.extract(file.uri) else null
    if (mp4 != null) {
      builder.title = mp4.title
      builder.artist = mp4.artist
      builder.album = mp4.album
      builder.genre = mp4.genre
      builder.narrator = mp4.narrator
      val durationMs = mp4.durationMs
      if (durationMs != null && mp4.tagsAreComplete) {
        builder.chapters += mp4.chapters
        return builder.build(durationMs.milliseconds)
      }
    }

    val (duration, trackGroups) = retrieveDurationAndMetadata(file.uri)
      ?: return null
    if (duration <= Duration.ZERO) {
      Logger.w("Duration is zero or negative for file: ${file.uri}")
      return null
    }

    repeat(trackGroups.length) { trackGroupsIndex ->
      val trackGroup = trackGroups[trackGroupsIndex]
      if (trackGroup.type == C.TRACK_TYPE_AUDIO) {
        repeat(trackGroup.length) { formatIndex ->
          val format = trackGroup.getFormat(formatIndex)
          format.metadata?.let { metadata ->
            repeat(metadata.length()) { metadataIndex ->
              when (val entry = metadata.get(metadataIndex)) {
                is TextInformationFrame -> visitText(entry, builder)
                is ChapterFrame -> visitChapter(entry, builder)
                is VorbisComment -> visitVorbis(entry, builder)
                is MdtaMetadataEntry -> visitMdta(entry, builder)
                else -> Logger.d("Unknown metadata entry: $entry")
              }
            }
          }
        }
      }
    }

    if (mp4 != null) {
      builder.chapters += mp4.chapters
    }
    if (fileType == FileTypes.MATROSKA || extension == "mka" || extension == "mkv") {
      parseMatroskaMetaData(file, builder)
    }

    return builder.build(duration)
  }

  private fun parseMatroskaMetaData(
    file: CachedDocumentFile,
    builder: Metadata.Builder,
  ) {
    try {
      matroskaExtractorFactory.create(file.uri).use { extractor ->
        val mediaInfo = extractor.readMediaInfo()
        builder.chapters.addAll(mediaInfo.chapters)
        builder.artist = builder.artist ?: mediaInfo.artist
        builder.album = builder.album ?: mediaInfo.album
        builder.title = builder.title ?: mediaInfo.title
      }
    } catch (e: MatroskaParseException) {
      Logger.w(e, "Error parsing Matroska metadata")
    }
  }

  private fun visitMdta(
    entry: MdtaMetadataEntry,
    builder: Metadata.Builder,
  ) {
    when (entry.key) {
      "com.apple.quicktime.title" -> {
        builder.title = entry.value.toString(Charsets.UTF_8)
      }
      "com.apple.quicktime.artist" -> {
        builder.artist = entry.value.toString(Charsets.UTF_8)
      }
      "com.apple.quicktime.album" -> {
        builder.album = entry.value.toString(Charsets.UTF_8)
      }
    }
  }

  private fun visitVorbis(
    entry: VorbisComment,
    builder: Metadata.Builder,
  ) {
    val key = entry.key
    val value = entry.value
    when {
      key == "ARTIST" -> builder.artist = value
      key == "ALBUM" -> builder.album = value
      key == "TITLE" -> builder.title = value
      key.startsWith("CHAPTER") -> {
        val withoutPrefix = key.removePrefix("CHAPTER")
        val isName = withoutPrefix.endsWith("NAME")
        if (isName) {
          val index = withoutPrefix.removeSuffix("NAME").toIntOrNull()
          if (index != null) {
            builder.vorbisChapterNames[index] = value
          }
        } else {
          val index = withoutPrefix.toIntOrNull()
          if (index != null) {
            val duration = parseVorbisDuration(value)
            if (duration != null) {
              builder.vorbisChapterStarts[index] = duration.inWholeMilliseconds
            }
          }
        }
      }
      else -> Logger.d("Unknown comment name: ${entry.key}, value: $value")
    }
  }

  private fun visitChapter(
    entry: ChapterFrame,
    builder: Metadata.Builder,
  ) {
    repeat(entry.subFrameCount) { subFrameIndex ->
      val subFrame = entry.getSubFrame(subFrameIndex)
      if (subFrame is TextInformationFrame) {
        builder.chapters.add(MarkData(startMs = entry.startTimeMs.toLong(), name = subFrame.values.first()))
      }
    }
  }

  private fun visitText(
    entry: TextInformationFrame,
    builder: Metadata.Builder,
  ) {
    val value = entry.values.first()
    when (entry.id) {
      "TIT2" -> builder.title = value
      "TPE1" -> builder.artist = value
      "TALB" -> builder.album = value
      "TCON" -> builder.genre = value
      "TCOM" -> builder.narrator = value
      "TXXX" -> when (entry.description) {
        "MVNM" -> builder.series = value
        "MVIN" -> builder.part = if (builder.part.isNullOrBlank()) value else builder.part
        "TXXX:PART" -> builder.part = value
        "TXXX:NARRATOR" -> builder.narrator = if (builder.narrator.isNullOrBlank()) value else builder.narrator
        else -> Logger.v("Unknown TXXX frame description:  ${entry.description}, value: $value")
      }
      "TRCK", "TYER", "TSSE" -> {}
      else -> Logger.v("Unknown frame ID: ${entry.id}, value: $value")
    }
  }

  private suspend fun retrieveDurationAndMetadata(uri: Uri): Pair<Duration, TrackGroupArray>? {
    return try {
      MetadataRetriever.Builder(context, MediaItem.fromUri(uri))
        .setMediaSourceFactory(mediaSourceFactory)
        .build()
        .use { retriever ->
          // run both retrievals on the same retriever instance so the file
          // is only opened and parsed once
          coroutineScope {
            val durationDeferred = async {
              runCatching { retriever.retrieveDurationUs().await().microseconds }
                .getOrElse {
                  if (it is CancellationException) throw it
                  Logger.w(it, "Error retrieving duration")
                  null
                }
            }
            val trackGroupsDeferred = async {
              runCatching { retriever.retrieveTrackGroups().await() }
                .getOrElse {
                  if (it is CancellationException) throw it
                  Logger.w(it, "Error retrieving metadata")
                  null
                }
            }
            val duration = durationDeferred.await() ?: return@coroutineScope null
            val trackGroups = trackGroupsDeferred.await() ?: return@coroutineScope null
            duration to trackGroups
          }
        }
    } catch (e: Exception) {
      if (e is CancellationException) currentCoroutineContext().ensureActive()
      Logger.w(e, "Error retrieving metadata")
      null
    }
  }
}
