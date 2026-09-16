package voice.core.scanner

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
  @MediaAnalysisSemaphore private val semaphore: Semaphore,
) {

  suspend fun scan(folders: Map<FolderType, List<CachedDocumentFile>>) {
    // bookshelf model: every registered folder or file is exactly one book,
    // regardless of the (legacy) folder type
    val files = folders.values.flatten()

    contentRepo.setAllInactiveExcept(files.map { BookId(it.uri) })

    val probeFile = files.findProbeFile()
    if (probeFile != null) {
      if (deviceHasPermissionBug.checkForBugAndSet(probeFile)) {
        Logger.w("Device has permission bug, aborting scan! Probed $probeFile")
        return
      }
    }

    // enumerate every book's chapters up front (directory listing only, no
    // media parsing) so the import progress has an exact total and the bar
    // never moves backwards
    val entries: List<BookEntry> = coroutineScope {
      files
        .map { bookFile ->
          async(Dispatchers.IO) {
            semaphore.withPermit {
              val audioFiles = bookFile.walk()
                .filter { it.isAudioFile() }
                .toList()
              BookEntry(bookFile, audioFiles)
            }
          }
        }
        .awaitAll()
    }

    scanProgressReporter.begin(
      booksTotal = entries.size,
      chaptersTotal = entries.sumOf { it.audioFiles.size },
    )

    coroutineScope {
      entries
        .map { entry ->
          async(Dispatchers.IO) {
            try {
              scan(entry)
            } catch (e: CancellationException) {
              throw e
            } catch (e: Exception) {
              Logger.w(e, "Error while scanning ${entry.bookFile}")
            } finally {
              scanProgressReporter.bookScanned()
            }
          }
        }
        .joinAll()
    }
  }

  private fun List<CachedDocumentFile>.findProbeFile(): CachedDocumentFile? {
    return asSequence().flatMap { it.walk() }
      .firstOrNull { child ->
        child.isAudioFile() && child.uri.authority == "com.android.externalstorage.documents"
      }
  }

  private suspend fun scan(entry: BookEntry) {
    val file = entry.bookFile
    val parseResult = chapterParser.parse(file, entry.audioFiles) { progress ->
      // store every batch so the book shows up in the library and can already
      // be played while its remaining chapters are still being analyzed
      storeChapters(file, progress.chapters, progress.firstChapterMetadata, isComplete = false)
    }
    storeChapters(file, parseResult.chapters, parseResult.firstChapterMetadata, isComplete = true)
  }

  private suspend fun storeChapters(
    file: CachedDocumentFile,
    chapters: List<Chapter>,
    firstChapterMetadata: Metadata?,
    isComplete: Boolean,
  ) {
    if (chapters.isEmpty()) return

    val content = bookParser.parseAndStore(chapters, file, firstChapterMetadata)

    val chapterIds = chapters.map { it.id }
    val currentChapterGone = content.currentChapter !in chapterIds
    if (!isComplete) {
      // an incomplete chapter list must only ever grow the book. Otherwise a
      // rescan would temporarily hide chapters and reset the stored position.
      if (chapterIds.size <= content.chapters.size || currentChapterGone) {
        return
      }
    }
    val currentChapter = if (currentChapterGone) chapterIds.first() else content.currentChapter
    val positionInChapter = if (currentChapterGone) 0 else content.positionInChapter
    val updated = content.copy(
      chapters = chapterIds,
      currentChapter = currentChapter,
      positionInChapter = positionInChapter,
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
