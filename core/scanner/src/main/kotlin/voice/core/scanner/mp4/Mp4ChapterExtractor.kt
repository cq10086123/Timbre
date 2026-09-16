package voice.core.scanner.mp4

import android.content.Context
import android.net.Uri
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import voice.core.data.MarkData
import voice.core.logging.api.Logger

@Inject
internal class Mp4ChapterExtractor(
  private val context: Context,
  private val boxParser: Mp4BoxParser,
  private val chapterTrackProcessor: ChapterTrackProcessor,
) {

  suspend fun extractChapters(uri: Uri): List<MarkData> = withContext(Dispatchers.IO) {
    Mp4BoxInput.create(context, uri).use { input ->
      try {
        input.open()
        val topLevelResult = boxParser(input)
        val trackId = topLevelResult.chapterTrackId
        when {
          topLevelResult.chplChapters.isNotEmpty() -> {
            topLevelResult.chplChapters
          }
          trackId != null -> {
            chapterTrackProcessor(uri, input.dataSource, trackId, topLevelResult)
          }
          else -> emptyList()
        }
      } catch (e: Exception) {
        Logger.w(e, "Failed to extract MP4 chapters")
        emptyList()
      }
    }
  }
}
