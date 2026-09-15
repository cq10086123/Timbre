package voice.core.scanner

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
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

  public fun scan(restartIfScanning: Boolean = false) {
    Logger.i("scanForFiles with restartIfScanning=$restartIfScanning")
    if (scanningJob?.isActive == true && !restartIfScanning) {
      return
    }
    val oldJob = scanningJob
    scanningJob = scope.launch {
      scannerActive.value = true
      oldJob?.cancelAndJoin()

      try {
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
        scanProgressReporter.finish()
        scannerActive.value = false
      }
    }
  }
}
