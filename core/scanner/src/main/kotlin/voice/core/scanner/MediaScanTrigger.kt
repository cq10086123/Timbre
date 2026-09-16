package voice.core.scanner

import android.os.SystemClock
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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import voice.core.data.BookId
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

  public val bookScanProgress: StateFlow<Map<BookId, BookScanProgress>>
    get() = scanProgressReporter.bookProgress

  private val scope = CoroutineScope(Dispatchers.IO)
  private var scanningJob: Job? = null

  /**
   * Identifies the scan that currently owns [scannerActive] and the reported
   * progress. A cancelled scan must not reset the state of the scan that
   * replaced it, otherwise the library stops showing the import progress.
   */
  private val scanGeneration = AtomicInteger()

  private var lastScanCompletedAt: Long? = null

  public fun scan(restartIfScanning: Boolean = false) {
    Logger.i("scanForFiles with restartIfScanning=$restartIfScanning")
    if (scanningJob?.isActive == true && !restartIfScanning) {
      return
    }
    // a scan queries every audio file through the documents provider, which is
    // also the path the player takes to open the next chapter. Re-scanning on
    // every shelf appearance therefore stalls the playback of big books, so an
    // unchanged library is only re-scanned after some time has passed. Adding
    // or removing a folder always restarts the scan.
    if (!restartIfScanning && skippedRecently()) {
      Logger.i("Skipping the scan because the previous one completed recently")
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
        // determinate chapter progress is done; the cover lookup has no
        // progress so the cards show their normal look while it runs
        scanProgressReporter.finish()
        val books = bookRepo.all()
        coverScanner.scan(books)
      } finally {
        if (scanGeneration.get() == generation) {
          scanProgressReporter.finish()
          scannerActive.value = false
          lastScanCompletedAt = SystemClock.elapsedRealtime()
        }
      }
    }
  }

  private fun skippedRecently(): Boolean {
    val lastScanCompletedAt = lastScanCompletedAt ?: return false
    return SystemClock.elapsedRealtime() - lastScanCompletedAt < MIN_SCAN_INTERVAL_MS
  }
}

private const val MIN_SCAN_INTERVAL_MS = 10 * 60 * 1000L
