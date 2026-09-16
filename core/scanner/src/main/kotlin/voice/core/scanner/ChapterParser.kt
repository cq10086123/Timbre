package voice.core.scanner

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
import kotlin.time.measureTime

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

  suspend fun parse(
    documentFile: CachedDocumentFile,
    onProgress: suspend (ChapterParseResult) -> Unit = { },
  ): ChapterParseResult {
    val audioFiles = documentFile.walk()
      .filter { it.isAudioFile() }
      .toList()
    return parse(documentFile, audioFiles, onProgress)
  }

  /**
   * Analyzes [audioFiles] in their natural order and reports the chapters that
   * are known after every batch through [onProgress].
   *
   * Reporting in order guarantees that a partially imported book always
   * contains the first chapters of the book, so it can already be played while
   * the remaining chapters are still being analyzed.
   */
  suspend fun parse(
    documentFile: CachedDocumentFile,
    audioFiles: List<CachedDocumentFile>,
    onProgress: suspend (ChapterParseResult) -> Unit = { },
  ): ChapterParseResult {
    // bulk load existing chapters so the per-file checks hit the cache instead
    // of issuing one SELECT per audio file
    chapterRepo.prefetch(audioFiles.map { ChapterId(it.uri) })

    val sortedFiles = audioFiles.sortedBy { ChapterId(it.uri) }
    val chapters = mutableListOf<Chapter>()
    val metadataByChapter = mutableMapOf<ChapterId, Metadata>()

    sortedFiles.chunked(PARSE_BATCH_SIZE).forEach { batch ->
      val batchDuration = measureTime {
        val newChapters = mutableListOf<Chapter>()
        parseBatch(batch).forEach { (chapter, metadata) ->
          chapters += chapter
          if (metadata != null) {
            metadataByChapter[chapter.id] = metadata
            newChapters += chapter
          }
        }
        // a single transaction per batch instead of one database write per file
        if (newChapters.isNotEmpty()) {
          chapterRepo.putAll(newChapters)
        }
      }
      Logger.i("analyzed ${chapters.size}/${sortedFiles.size} chapters of $documentFile in $batchDuration")
      onProgress(parseResult(chapters, metadataByChapter))
    }

    return parseResult(chapters, metadataByChapter)
  }

  private suspend fun parseBatch(batch: List<CachedDocumentFile>): List<Pair<Chapter, Metadata?>> {
    return coroutineScope {
      batch
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
        .awaitAll()
        .filterNotNull()
    }
  }

  private fun parseResult(
    chapters: List<Chapter>,
    metadataByChapter: Map<ChapterId, Metadata>,
  ): ChapterParseResult {
    return ChapterParseResult(
      chapters = chapters.toList(),
      firstChapterMetadata = chapters.firstOrNull()?.let { metadataByChapter[it.id] },
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

private const val PARSE_BATCH_SIZE = 20
