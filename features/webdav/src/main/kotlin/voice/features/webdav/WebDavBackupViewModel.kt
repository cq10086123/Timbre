package voice.features.webdav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import voice.core.common.DispatcherProvider
import voice.core.common.MainScope
import voice.core.webdav.WebDavBackupItem
import voice.core.webdav.WebDavBackupListResult
import voice.core.webdav.WebDavBackupLocation
import voice.core.webdav.WebDavBackupResult
import voice.core.webdav.WebDavBackupService
import voice.core.webdav.WebDavBackupTargetStatus
import voice.core.webdav.WebDavRestoreResult
import voice.navigation.Destination
import voice.navigation.Navigator
import voice.navigation.Origin

/** What the backup rows are doing, or what they did last. */
sealed interface WebDavBackupUiState {
  data object Idle : WebDavBackupUiState

  data object Busy : WebDavBackupUiState

  data class BackupDone(
    val books: Int,
    val server: String,
    val label: String?,
  ) : WebDavBackupUiState

  data class RestoreDone(
    val restored: Int,
    val total: Int,
    val server: String,
    val label: String?,
  ) : WebDavBackupUiState

  /** No server is configured, or its password cannot be decrypted. */
  data object NoServer : WebDavBackupUiState

  /** A server is there, but no backup folder was picked yet. */
  data object NoFolder : WebDavBackupUiState

  /** The shelf holds nothing; backing up would only wipe the previous backup. */
  data object EmptyShelf : WebDavBackupUiState

  /** Books are still being indexed, their chapters are not stored yet. */
  data class BooksLoading(val count: Int) : WebDavBackupUiState

  /** The server answered but holds no backup file. */
  data object NoBackupFile : WebDavBackupUiState

  data class Failed(val reason: String) : WebDavBackupUiState
}

data class WebDavBackupViewState(
  val backupState: WebDavBackupUiState = WebDavBackupUiState.Idle,
  /** Where the backup goes, null until a folder was picked. */
  val location: WebDavBackupLocation? = null,
  /** Label dialog, non-null while it is open. */
  val backupDialog: BackupDialog? = null,
  /** Restore picker, non-null while it is open. */
  val restorePicker: RestorePicker? = null,
  /** Set while the "pick a folder first" reminder is on screen. */
  val noFolderPrompt: Boolean = false,
) {
  /** Folder below the server base directory, "/" for the base itself. */
  val folder: String
    get() {
      val current = location ?: return ""
      return current.folderUrl.removePrefix(current.serverBaseUrl).ifEmpty { "/" }
    }

  data class BackupDialog(
    val label: String,
    /** Backups already in the folder; empty until the listing arrives. */
    val existing: List<WebDavBackupItem> = emptyList(),
    val loadingExisting: Boolean = false,
  )

  sealed interface RestorePicker {
    data object Loading : RestorePicker

    class Loaded(val items: List<WebDavBackupItem>) : RestorePicker

    class Failed(val reason: String?) : RestorePicker
  }
}

/** The "backup and restore" page: folder, backing up, restoring. */
@AssistedInject
class WebDavBackupViewModel(
  private val backupService: WebDavBackupService,
  dispatcherProvider: DispatcherProvider,
  private val navigator: Navigator,
  @Assisted
  private val origin: Origin,
) {

  private val scope = MainScope(dispatcherProvider)
  private val backupState = MutableStateFlow<WebDavBackupUiState>(WebDavBackupUiState.Idle)
  private val backupDialog = MutableStateFlow<WebDavBackupViewState.BackupDialog?>(null)
  private val restorePicker = MutableStateFlow<WebDavBackupViewState.RestorePicker?>(null)
  private val noFolderPrompt = MutableStateFlow(false)

  @Composable
  fun viewState(): WebDavBackupViewState {
    val state by backupState.collectAsState()
    val locationFlow = remember { backupService.location() }
    val location by locationFlow.collectAsState(initial = null)
    val dialog by backupDialog.collectAsState()
    val picker by restorePicker.collectAsState()
    val prompt by noFolderPrompt.collectAsState()
    return WebDavBackupViewState(
      backupState = state,
      location = location,
      backupDialog = dialog,
      restorePicker = picker,
      noFolderPrompt = prompt,
    )
  }

  fun openBackupFolder() {
    navigator.goTo(Destination.WebDavBackupFolder(origin))
  }

  /** The backup row: without a folder there is nothing to write into. */
  fun backup() {
    if (backupState.value == WebDavBackupUiState.Busy) return
    backupState.value = WebDavBackupUiState.Idle
    scope.launch {
      if (!requireTarget()) return@launch
      // a book whose import is still running would be written half-indexed,
      // so the label is only asked for once nothing is pending
      val pending = backupService.pendingImports()
      if (pending > 0) {
        backupState.value = WebDavBackupUiState.BooksLoading(pending)
        return@launch
      }
      backupDialog.value = WebDavBackupViewState.BackupDialog(
        label = backupService.lastLabel().orEmpty(),
        loadingExisting = true,
      )
      // a listing failure is not fatal: the user then types the label instead
      // of picking one of the backups that are already there
      val items = (backupService.backups() as? WebDavBackupListResult.Success)?.items.orEmpty()
      backupDialog.value = backupDialog.value?.copy(existing = items, loadingExisting = false)
    }
  }

  fun backupLabelChanged(value: String) {
    backupDialog.value = backupDialog.value?.copy(label = value)
  }

  fun dismissBackupDialog() {
    backupDialog.value = null
  }

  fun confirmBackup() {
    val label = backupDialog.value?.label?.trim()?.ifEmpty { null }
    backupDialog.value = null
    if (backupState.value == WebDavBackupUiState.Busy) return
    backupState.value = WebDavBackupUiState.Busy
    scope.launch {
      backupState.value = when (val result = backupService.backup(label)) {
        is WebDavBackupResult.Success -> {
          backupService.rememberLabel(result.label)
          WebDavBackupUiState.BackupDone(
            books = result.bookCount,
            server = result.serverName,
            label = result.label,
          )
        }
        WebDavBackupResult.NoServer -> WebDavBackupUiState.NoServer
        WebDavBackupResult.NoFolder -> WebDavBackupUiState.NoFolder
        WebDavBackupResult.EmptyShelf -> WebDavBackupUiState.EmptyShelf
        is WebDavBackupResult.BooksLoading -> WebDavBackupUiState.BooksLoading(result.count)
        is WebDavBackupResult.Failure -> WebDavBackupUiState.Failed(result.reason)
      }
    }
  }

  /** The restore row: lists what the folder holds so one backup can be picked. */
  fun restore() {
    if (backupState.value == WebDavBackupUiState.Busy) return
    backupState.value = WebDavBackupUiState.Idle
    scope.launch {
      if (!requireTarget()) return@launch
      restorePicker.value = WebDavBackupViewState.RestorePicker.Loading
      restorePicker.value = when (val result = backupService.backups()) {
        is WebDavBackupListResult.Success -> WebDavBackupViewState.RestorePicker.Loaded(result.items)
        WebDavBackupListResult.NoServer -> {
          backupState.value = WebDavBackupUiState.NoServer
          null
        }
        WebDavBackupListResult.NoFolder -> {
          backupState.value = WebDavBackupUiState.NoFolder
          null
        }
        is WebDavBackupListResult.Failure -> WebDavBackupViewState.RestorePicker.Failed(result.reason)
      }
    }
  }

  fun dismissRestorePicker() {
    restorePicker.value = null
  }

  fun restoreBackup(label: String?) {
    restorePicker.value = null
    if (backupState.value == WebDavBackupUiState.Busy) return
    backupState.value = WebDavBackupUiState.Busy
    scope.launch {
      backupState.value = when (val result = backupService.restore(label)) {
        is WebDavRestoreResult.Success ->
          WebDavBackupUiState.RestoreDone(
            restored = result.restored,
            total = result.total,
            server = result.serverName,
            label = result.label,
          )
        WebDavRestoreResult.NoServer -> WebDavBackupUiState.NoServer
        WebDavRestoreResult.NoFolder -> WebDavBackupUiState.NoFolder
        WebDavRestoreResult.NoBackupFile -> WebDavBackupUiState.NoBackupFile
        is WebDavRestoreResult.Failure -> WebDavBackupUiState.Failed(result.reason)
      }
    }
  }

  fun dismissNoFolderPrompt() {
    noFolderPrompt.value = false
  }

  /** The reminder sends the user straight to the folder picker. */
  fun pickFolderFromPrompt() {
    noFolderPrompt.value = false
    navigator.goTo(Destination.WebDavBackupFolder(origin))
  }

  fun back() {
    navigator.goBack()
  }

  /**
   * A backup needs a server and a folder the user picked. Reports which of the
   * two is missing instead of failing later against a guessed path.
   */
  private suspend fun requireTarget(): Boolean {
    return when (backupService.targetStatus()) {
      WebDavBackupTargetStatus.Ready -> true
      WebDavBackupTargetStatus.NoServer -> {
        backupState.value = WebDavBackupUiState.NoServer
        false
      }
      WebDavBackupTargetStatus.NoFolder -> {
        noFolderPrompt.value = true
        false
      }
    }
  }

  @AssistedFactory
  interface Factory {
    fun create(origin: Origin): WebDavBackupViewModel
  }
}
