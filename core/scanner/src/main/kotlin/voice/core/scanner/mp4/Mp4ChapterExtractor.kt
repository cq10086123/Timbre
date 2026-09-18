package voice.core.scanner.mp4

import android.net.Uri
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import voice.core.logging.api.Logger
import voice.core.webdav.WebDavDataSourceFactory

@Inject
internal class Mp4ChapterExtractor(
  private val boxParser: Mp4BoxParser,
  private val chapterTrackProcessor: ChapterTrackProcessor,
  dataSourceFactory: WebDavDataSourceFactory,
) {

  /**
   * Reads the duration, the tags and the chapters of an mp4 style file from its
   * boxes.
   *
   * Only the header boxes are read, the audio payload is skipped instead of read
   * through. Returns null when the file could not be parsed at all, so the
   * caller can fall back to preparing it with exoplayer.
   */
  suspend fun extract(uri: Uri): Mp4FileMetadata? = withContext(Dispatchers.IO) {
    Mp4BoxInput.create(uri, dataSourceFactory.createDataSource()).use { input ->
      try {
        input.open()
        val output = boxParser(input)
        val trackId = output.chapterTrackId
        val chapters = when {
          output.chplChapters.isNotEmpty() -> output.chplChapters
          trackId != null -> chapterTrackProcessor(uri, input.dataSource, trackId, output)
          else -> emptyList()
        }
        Mp4FileMetadata(
          chapters = chapters,
          durationMs = output.audioDurationMs,
          title = output.title,
          artist = output.artist,
          album = output.album,
          genre = output.genre,
          narrator = output.narrator,
          tagsAreComplete = !output.tagsNeedRetriever,
        )
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Logger.w(e, "Failed to parse the boxes of $uri")
        null
      }
    }
  }
}
