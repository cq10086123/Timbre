package voice.features.sourceManager

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import voice.core.common.DispatcherProvider
import voice.core.common.MainScope
import voice.core.source.InstalledJdrPackage
import voice.core.source.JdrPackageManager
import voice.navigation.Navigator

data class SourceManagerViewState(
  val packages: List<InstalledJdrPackage> = emptyList(),
  val busy: Boolean = false,
  /** Import dialog with the last used url, null when closed. */
  val urlDialog: Boolean = false,
  val message: String? = null,
  val deleteCandidate: InstalledJdrPackage? = null,
)

interface SourceManagerListener {
  fun openImportDialog()
  fun dismissImportDialog()
  fun confirmImport(url: String)
  fun togglePackage(pkg: InstalledJdrPackage, enabled: Boolean)
  fun toggleSource(pkg: InstalledJdrPackage, sourceId: String, enabled: Boolean)
  fun requestDelete(pkg: InstalledJdrPackage)
  fun dismissDelete()
  fun confirmDelete()
  fun dismissMessage()
  fun close()
}

@AssistedInject
class SourceManagerViewModel(
  private val manager: JdrPackageManager,
  dispatcherProvider: DispatcherProvider,
  private val navigator: Navigator,
) : SourceManagerListener {

  private val scope = MainScope(dispatcherProvider)

  private val busy = MutableStateFlow(false)
  private val urlDialog = MutableStateFlow(false)
  private val message = MutableStateFlow<String?>(null)
  private val deleteCandidate = MutableStateFlow<InstalledJdrPackage?>(null)

  @Composable
  fun viewState(): SourceManagerViewState {
    val packages by manager.state.collectAsState()
    val busyState by busy.collectAsState()
    val urlDialogState by urlDialog.collectAsState()
    val messageState by message.collectAsState()
    val deleteState by deleteCandidate.collectAsState()
    return SourceManagerViewState(
      packages = packages.packages,
      busy = busyState,
      urlDialog = urlDialogState,
      message = messageState,
      deleteCandidate = deleteState,
    )
  }

  @AssistedFactory
  fun interface Factory {
    fun create(): SourceManagerViewModel
  }

  override fun openImportDialog() {
    urlDialog.value = true
  }

  override fun dismissImportDialog() {
    urlDialog.value = false
  }

  override fun confirmImport(url: String) {
    if (url.isBlank()) return
    urlDialog.value = false
    launchCatching { val _ = manager.installFromUrl(url.trim()) }
  }

  /** Called by the screen after the SAF picker returned the package bytes. */
  fun importFileBytes(bytes: ByteArray, fileName: String) {
    launchCatching { val _ = manager.install(bytes, origin = fileName) }
  }

  override fun togglePackage(pkg: InstalledJdrPackage, enabled: Boolean) {
    launchCatching { manager.setPackageEnabled(pkg.manifest.packageId, enabled) }
  }

  override fun toggleSource(pkg: InstalledJdrPackage, sourceId: String, enabled: Boolean) {
    launchCatching { manager.setSourceEnabled(pkg.manifest.packageId, sourceId, enabled) }
  }

  override fun requestDelete(pkg: InstalledJdrPackage) {
    deleteCandidate.value = pkg
  }

  override fun dismissDelete() {
    deleteCandidate.value = null
  }

  override fun confirmDelete() {
    val candidate = deleteCandidate.value ?: return
    deleteCandidate.value = null
    launchCatching { manager.uninstall(candidate.manifest.packageId) }
  }

  override fun dismissMessage() {
    message.value = null
  }

  override fun close() {
    navigator.goBack()
  }

  private fun launchCatching(block: suspend () -> Unit) {
    if (busy.value) return
    busy.value = true
    scope.launch {
      try {
        block()
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        message.value = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
      } finally {
        busy.value = false
      }
    }
  }
}
