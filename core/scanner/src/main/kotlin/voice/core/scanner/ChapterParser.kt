package voice.core.scanner

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import voice.core.common.PlaybackIoGate
import voice.core.data.BookId
import voice.core.data.Chapter
import voice.core.data.ChapterId
import voice.core.data.isAudioFile
import voice.core.data.repo.ChapterRepo
import voice.core.documentfile.CachedDocumentFile
import voice.core.documentfile.walk
import voice.core.logging.api.Logger
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

internal data class ChapterParseResult(
  val chapters: List<Chapter>,
  val firstChapterMetadata: Metadata?,
  /**
   * How many of the analyzed audio files could not be read. They are missing
   * from [chapters], which the import reports to the shelf instead of silently
   * importing a book with missing chapters.
   */
  val failedChapters: Int = 0,
)

@Inject
internal class ChapterParser(
  private val chapterRepo: ChapterRepo,
  private val mediaAnalyzer: MediaAnalyzer,
  private val scanProgressReporter: ScanProgressReporter,
  private val playbackIoGate: PlaybackIoGate,
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
   *
   * Within a batch the chapters are reported as soon as they are known instead
   * of only after the whole batch: a listener at the import frontier gets the
   * next chapter after a single file analysis, not after the analysis time of
   * an entire batch (with hundreds of episodes this is tens of seconds). For
   * big books the reporting steps widen, because every report rewrites the
   * chapter list of the book and that would multiply with the chapter count.
   */
  suspend fun parse(
    documentFile: CachedDocumentFile,
    audioFiles: List<CachedDocumentFile>,
    onProgress: suspend (ChapterParseResult) -> Unit = { },
  ): ChapterParseResult {
    val bookId = BookId(documentFile.uri)
    // bulk load existing chapters so the per-file checks hit the cache instead
    // of issuing one SELECT per audio file
    chapterRepo.prefetch(audioFiles.map { ChapterId(it.uri) })

    val sortedFiles = audioFiles.sortedBy { ChapterId(it.uri) }
    val chapters = mutableListOf<Chapter>()
    val metadataByChapter = mutableMapOf<ChapterId, Metadata>()
    val publishStride = publishStride(sortedFiles.size)
    var failedChapters = 0

    sortedFiles.chunked(PARSE_BATCH_SIZE).forEach { batch ->
      val batchDuration = measureTime {
        val unpublishedChapters = mutableListOf<Chapter>()
        coroutineScope {
          val deferred = batch.map { file ->
            async(Dispatchers.IO) {
              playbackIoGate.withScannerIoSlot(analyzeSemaphore) {
                try {
                  parseChapter(file)
                } finally {
                  scanProgressReporter.chapterScanned(bookId)
                }
              }
            }
          }

          deferred.forEachIndexed { index, chapterDeferred ->
            val (chapter, metadata) = chapterDeferred.await() ?: run {
              failedChapters++
              return@forEachIndexed
            }
            chapters += chapter
            if (metadata != null) {
              metadataByChapter[chapter.id] = metadata
              unpublishedChapters += chapter
            }
            val isLastOfBatch = index == deferred.lastIndex
            if (isLastOfBatch || chapters.size % publishStride == 0) {
              // the chapters have to exist before the book content references
              // them, otherwise the book can't be assembled while it plays
              if (unpublishedChapters.isNotEmpty()) {
                chapterRepo.putAll(unpublishedChapters)
                unpublishedChapters.clear()
              }
              onProgress(parseResult(chapters, metadataByChapter, failedChapters))
            }
          }
        }
      }
      Logger.i("analyzed ${chapters.size}/${sortedFiles.size} chapters of $documentFile in $batchDuration")
      onProgress(parseResult(chapters, metadataByChapter, failedChapters))
    }

    return parseResult(chapters, metadataByChapter, failedChapters)
  }

  private fun publishStride(chapterCount: Int): Int {
    // storing a chapter rewrites the chapter list of the book, which for a
    // book with thousands of chapters is a big row. Bounding the number of
    // reports to ~250 per import keeps that work in the same order as before
    // while small and mid sized books report every single chapter.
    return (chapterCount / MAX_CHAPTER_REPORTS).coerceAtLeast(1)
  }

  private fun parseResult(
    chapters: List<Chapter>,
    metadataByChapter: Map<ChapterId, Metadata>,
    failedChapters: Int,
  ): ChapterParseResult {
    return ChapterParseResult(
      chapters = chapters.toList(),
      firstChapterMetadata = chapters.firstOrNull()?.let { metadataByChapter[it.id] },
      failedChapters = failedChapters,
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
      var analyzed: Metadata? = null
      val analysisDuration = measureTime {
        // the io slot that guards this analysis was taken while holding the
        // playback gate, so playback already has the storage here
        analyzed = mediaAnalyzer.analyze(file)
      }
      if (analysisDuration >= SLOW_ANALYSIS_LOG_THRESHOLD) {
        Logger.w("Analyzing $id took $analysisDuration")
      }
      analyzed
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
private const val MAX_CHAPTER_REPORTS = 250
private val SLOW_ANALYSIS_LOG_THRESHOLD: Duration = 2.seconds
