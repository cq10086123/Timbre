package voice.core.scanner

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import voice.core.data.folders.AudiobookFolders
import voice.core.data.folders.FolderType
import voice.core.data.repo.BookRepository
import voice.core.documentfile.CachedDocumentFile
import voice.core.logging.api.Logger
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.measureTime

@SingleIn(AppScope::class)
@Inject
public class MediaScanTrigger
internal constructor(
  private val audiobookFolders: AudiobookFolders,
  private val scanner: MediaScanner,
  private val coverScanner: CoverScanner,
  private val bookRepo: BookRepository,
  private val scanProgressReporter: ScanProgressReporter,
) {

  public val scannerActive: Flow<Boolean>
    field = MutableStateFlow(false)

  public val scanProgress: Flow<ScanProgress?> = scanProgressReporter.progress

  private val scope = CoroutineScope(Dispatchers.IO)
  private var scanningJob: Job? = null

  /**
   * Identifies the scan that currently owns [scannerActive] and the reported
   * progress. A cancelled scan must not reset the state of the scan that
   * replaced it, otherwise the library stops showing the import progress.
   */
  private val scanGeneration = AtomicInteger()

  public fun scan(restartIfScanning: Boolean = false) {
    Logger.i("scanForFiles with restartIfScanning=$restartIfScanning")
    if (scanningJob?.isActive == true && !restartIfScanning) {
      return
    }
    val previousJob = scanningJob
    val generation = scanGeneration.incrementAndGet()
    scanningJob = scope.launch {
      // the replaced scan resets the shared state in its finally block, so it
      // has to be fully stopped before this one claims the state
      previousJob?.cancelAndJoin()
      if (scanGeneration.get() != generation) {
        return@launch
      }
      scannerActive.value = true
      scanProgressReporter.finish()

      try {
        // load the books and their chapters into the caches while we are in the
        // background. Playback reads them on the main thread, where a cold
        // cache of a big library would block for a noticeable time.
        measureTime {
          try {
            val books = bookRepo.all()
            Logger.i("warmed up the book cache with ${books.size} books")
          } catch (e: CancellationException) {
            throw e
          } catch (e: Exception) {
            Logger.w(e, "Could not warm up the book cache")
          }
        }.also {
          Logger.i("warming up the book cache took $it")
        }
        measureTime {
          audiobookFolders.migrateLegacyFolders()
          val folders: Map<FolderType, List<CachedDocumentFile>> = audiobookFolders.all()
            .first()
            .mapValues { (_, documentFilesWithUri) ->
              documentFilesWithUri.map { it.documentFile }
            }
          scanner.scan(folders)
        }.also {
          Logger.i("scan took $it")
        }
        // determinate chapter progress is done; cover lookup has no progress
        // so the UI falls back to an indeterminate bar while this runs
        scanProgressReporter.finish()
        val books = bookRepo.all()
        coverScanner.scan(books)
      } finally {
        if (scanGeneration.get() == generation) {
          scanProgressReporter.finish()
          scannerActive.value = false
        }
      }
    }
  }
}
