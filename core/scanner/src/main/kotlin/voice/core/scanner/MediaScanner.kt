package voice.core.scanner

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import voice.core.common.PlaybackIoGate
import voice.core.data.BookId
import voice.core.data.Chapter
import voice.core.data.folders.FolderType
import voice.core.data.isAudioFile
import voice.core.data.repo.BookContentRepo
import voice.core.documentfile.CachedDocumentFile
import voice.core.documentfile.walk
import voice.core.logging.api.Logger

@Inject
internal class MediaScanner(
  private val contentRepo: BookContentRepo,
  private val chapterParser: ChapterParser,
  private val bookParser: BookParser,
  private val deviceHasPermissionBug: DeviceHasStoragePermissionBug,
  private val scanProgressReporter: ScanProgressReporter,
  private val playbackIoGate: PlaybackIoGate,
) {

  private val bookSemaphore = Semaphore(BOOK_SCAN_CONCURRENCY)

  suspend fun scan(
    folders: Map<FolderType, List<CachedDocumentFile>>,
    hasRegisteredFolders: Boolean = true,
    analysisSemaphore: Semaphore = Semaphore(1),
  ) {
    // bookshelf model: every registered folder or file is exactly one book,
    // regardless of the (legacy) folder type
    val files = folders.values.flatten()

    if (files.isEmpty() && hasRegisteredFolders) {
      // folders are registered but none of them resolved, e.g. the persisted
      // uri permissions were lost on an update. Deactivating everything now
      // would empty the whole shelf even though every book is still there, so
      // the scan is skipped until the folders are accessible again.
      Logger.w("Skipping the scan because no registered folder is accessible")
      return
    }

    contentRepo.setAllInactiveExcept(files.map { BookId(it.uri) })

    val probeFile = files.findProbeFile()
    if (probeFile != null) {
      if (deviceHasPermissionBug.checkForBugAndSet(probeFile)) {
        Logger.w("Device has permission bug, aborting scan! Probed $probeFile")
        return
      }
    }

    // report every book before anything is analyzed so its card shows up on
    // the shelf right away instead of only after the first chapters were
    // parsed. The chapter total is still unknown here, which the cards show
    // as an indeterminate progress line.
    files.forEach { bookFile ->
      scanProgressReporter.beginBook(bookId = BookId(bookFile.uri), chaptersTotal = 0)
    }

    coroutineScope {
      files
        .map { bookFile ->
          async(Dispatchers.IO) {
            bookSemaphore.withPermit {
              scanBook(bookFile, analysisSemaphore)
            }
          }
        }
        .joinAll()
    }
  }

  private suspend fun scanBook(
    bookFile: CachedDocumentFile,
    analysisSemaphore: Semaphore,
  ) {
    val bookId = BookId(bookFile.uri)
    var error: BookScanError? = null
    try {
      // enumerate this book's chapters first (directory listing only,
      // no media parsing) so the import progress has an exact total
      // and the bar never moves backwards. A book starts analyzing as
      // soon as its own listing is known; one book's directory walk
      // no longer delays the import of the others. The walk goes
      // through the documents provider as well, so it waits for
      // playback before it takes an io slot.
      val audioFiles = playbackIoGate.withScannerIoSlot(analysisSemaphore) {
        bookFile.walk()
          .transform { if (it.isAudioFile()) emit(it) }
          .toList()
      }
      val readError = bookFile.error
      if (audioFiles.isEmpty() && readError != null) {
        // an unreadable folder is not an empty one. Its stored
        // chapters are kept, so the book stays on the shelf (with an
        // error on its card) instead of silently disappearing
        Logger.w(readError, "Could not read the folder of $bookId")
        error = BookScanError(bookId = bookId, kind = BookScanError.Kind.Unreachable)
      }
      scanProgressReporter.beginBook(
        bookId = bookId,
        chaptersTotal = audioFiles.size,
      )
      if (audioFiles.isNotEmpty()) {
        val result = scan(BookEntry(bookFile, audioFiles), analysisSemaphore)
        if (result.failedChapters > 0) {
          error = BookScanError(
            bookId = bookId,
            kind = BookScanError.Kind.AnalysisFailed,
            failedChapters = result.failedChapters,
          )
        }
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Logger.w(e, "Error while scanning $bookFile")
      error = BookScanError(bookId = bookId, kind = BookScanError.Kind.Unreachable)
    } finally {
      scanProgressReporter.finishBook(bookId)
      val scanError = error
      if (scanError == null) {
        scanProgressReporter.clearError(bookId)
      } else {
        // reported through the shelf, which shows the error on the
        // card of the book with a retry: a failed import must not stay
        // invisible
        scanProgressReporter.reportError(scanError)
      }
    }
  }

  private suspend fun List<CachedDocumentFile>.findProbeFile(): CachedDocumentFile? {
    return asFlow()
      // the storage permission bug only exists on the local external storage;
      // walking a remote book would only cost a network request per folder
      .filter { it.uri.scheme != "http" && it.uri.scheme != "https" }
      .flatMapConcat { it.walk() }
      .transform { child ->
        if (child.isAudioFile() && child.uri.authority == "com.android.externalstorage.documents") {
          emit(child)
        }
      }
      .firstOrNull()
  }

  private suspend fun scan(
    entry: BookEntry,
    analysisSemaphore: Semaphore,
  ): ChapterParseResult {
    val file = entry.bookFile
    val parseResult = chapterParser.parse(file, entry.audioFiles, analysisSemaphore) { progress ->
      // store every batch so the book shows up in the library and can already
      // be played while its remaining chapters are still being analyzed
      storeChapters(file, progress.chapters, progress.firstChapterMetadata, isComplete = false)
    }
    storeChapters(
      file,
      parseResult.chapters,
      parseResult.firstChapterMetadata,
      isComplete = true,
      failedChapters = parseResult.failedChapters,
    )
    return parseResult
  }

  private suspend fun storeChapters(
    file: CachedDocumentFile,
    chapters: List<Chapter>,
    firstChapterMetadata: Metadata?,
    isComplete: Boolean,
    failedChapters: Int = 0,
  ) {
    if (chapters.isEmpty()) return

    val content = bookParser.parseAndStore(chapters, file, firstChapterMetadata)

    val chapterIds = chapters.map { it.id }
    val currentChapterGone = content.currentChapter !in chapterIds
    if (!isComplete || failedChapters > 0) {
      // an incomplete or partially failed chapter list must only ever grow
      // the book. Otherwise a rescan would permanently drop chapters that
      // merely failed to analyze (flaky network, corrupt header) and silently
      // move the stored position. Genuinely deleted files are picked up by the
      // next fully successful scan instead.
      if (chapterIds.size <= content.chapters.size || currentChapterGone) {
        return
      }
    }
    // The chapter the user was listening to is no longer part of the book: it
    // was deleted remotely, or the files were renamed in bulk. Falling back to
    // the first chapter would throw away the position in a book with hundreds
    // of episodes, so the chapter at the same index takes over - with a rename
    // that is the same episode, and the position survives as well.
    val previousIndex = content.chapters.indexOf(content.currentChapter)
    val fallbackIndex = previousIndex.coerceIn(0, chapterIds.lastIndex)
    val currentChapter = if (currentChapterGone) chapterIds[fallbackIndex] else content.currentChapter
    val updated = content.copy(
      chapters = chapterIds,
      currentChapter = currentChapter,
      positionInChapter = content.positionInChapter,
      isActive = true,
    )
    if (content != updated) {
      validateIntegrity(updated, chapters)
      contentRepo.put(updated)
    }
  }

  private data class BookEntry(
    val bookFile: CachedDocumentFile,
    val audioFiles: List<CachedDocumentFile>,
  )
}

internal const val MAX_IMPORT_PARALLELISM = 3

// how many books the import processes at the same time: bounds the heap
// retained by listings and in-progress chapter lists during mass imports
private const val BOOK_SCAN_CONCURRENCY = 4
