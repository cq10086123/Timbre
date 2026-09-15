package voice.core.scanner

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import voice.core.data.Chapter
import voice.core.data.ChapterId
import voice.core.data.isAudioFile
import voice.core.data.repo.ChapterRepo
import voice.core.documentfile.CachedDocumentFile
import voice.core.documentfile.walk
import voice.core.logging.api.Logger
import java.time.Instant

internal data class ChapterParseResult(
  val chapters: List<Chapter>,
  val firstChapterMetadata: Metadata?,
)

@Inject
internal class ChapterParser(
  private val chapterRepo: ChapterRepo,
  private val mediaAnalyzer: MediaAnalyzer,
  private val scanProgressReporter: ScanProgressReporter,
  @MediaAnalysisSemaphore private val analyzeSemaphore: Semaphore,
) {

  suspend fun parse(documentFile: CachedDocumentFile): ChapterParseResult {
    val audioFiles = documentFile.walk()
      .filter { it.isAudioFile() }
      .toList()
    return parse(documentFile, audioFiles)
  }

  suspend fun parse(
    documentFile: CachedDocumentFile,
    audioFiles: List<CachedDocumentFile>,
  ): ChapterParseResult {
    // bulk load existing chapters so the per-file checks hit the cache instead
    // of issuing one SELECT per audio file
    chapterRepo.prefetch(audioFiles.map { ChapterId(it.uri) })

    val parsed = coroutineScope {
      audioFiles
        .map { file ->
          async(Dispatchers.IO) {
            analyzeSemaphore.withPermit {
              try {
                parseChapter(file)
              } finally {
                scanProgressReporter.chapterScanned()
              }
            }
          }
        }
        .map { it.await() }
        .filterNotNull()
    }

    // a single transaction for all newly analyzed chapters instead of one
    // database write per file
    val newlyCreated = parsed.mapNotNull { (chapter, metadata) ->
      chapter.takeIf { metadata != null }
    }
    if (newlyCreated.isNotEmpty()) {
      chapterRepo.putAll(newlyCreated)
    }

    val analyzedMetadata = parsed
      .mapNotNull { (chapter, metadata) ->
        metadata?.let { chapter.id to it }
      }
      .toMap()

    val chapters = parsed.map { it.first }.sorted()

    return ChapterParseResult(
      chapters = chapters,
      firstChapterMetadata = chapters.firstOrNull()?.let { analyzedMetadata[it.id] },
    )
  }

  private suspend fun parseChapter(file: CachedDocumentFile): Pair<Chapter, Metadata?>? {
    val id = ChapterId(file.uri)
    val lastModified = Instant.ofEpochMilli(file.lastModified)
    val fileSize = file.length

    val cached = try {
      chapterRepo.get(id)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Logger.w(e, "Error loading cached chapter $id")
      null
    }
    if (cached != null && cached.fileLastModified == lastModified && cached.fileSize == fileSize) {
      return cached to null
    }

    val metadata = try {
      mediaAnalyzer.analyze(file)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Logger.w(e, "Error analyzing $id")
      null
    } ?: return null
    val chapter = Chapter(
      id = id,
      duration = metadata.duration,
      fileLastModified = lastModified,
      name = metadata.title ?: metadata.fileName,
      markData = metadata.chapters,
      fileSize = fileSize,
    )
    return chapter to metadata
  }
}
