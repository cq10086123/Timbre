package voice.features.webdav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.datastore.core.DataStore
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import voice.core.common.DispatcherProvider
import voice.core.common.MainScope
import voice.core.data.store.AnalysisParallelismStore
import voice.core.webdav.WebDavLibrary
import voice.core.webdav.WebDavServer
import voice.navigation.Destination
import voice.navigation.Navigator
import voice.navigation.Origin

data class WebDavSettingsViewState(
  /** Server picker of the import page, non-null while it is open. */
  val serverPicker: List<WebDavServer>? = null,
  /** Chapters analysed at the same time while importing. */
  val importParallelism: Int = 1,
  /** True while the concurrency picker of the import page is open. */
  val showingImportParallelismDialog: Boolean = false,
)

/**
 * The WebDAV section: three sub-pages - importing books, backup and restore,
 * servers - and the server picker the import page needs before it can browse.
 */
@AssistedInject
class WebDavSettingsViewModel(
  private val webDavLibrary: WebDavLibrary,
  @AnalysisParallelismStore
  private val analysisParallelismStore: DataStore<Int>,
  dispatcherProvider: DispatcherProvider,
  private val navigator: Navigator,
  @Assisted
  private val origin: Origin,
) {

  private val scope = MainScope(dispatcherProvider)
  private val serverPicker = MutableStateFlow<List<WebDavServer>?>(null)
  private val importParallelismDialog = mutableStateOf(false)

  @Composable
  fun viewState(): WebDavSettingsViewState {
    val servers by serverPicker.collectAsState()
    val importParallelism by analysisParallelismStore.data.collectAsState(initial = 1)
    return WebDavSettingsViewState(
      serverPicker = servers,
      importParallelism = importParallelism,
      showingImportParallelismDialog = importParallelismDialog.value,
    )
  }

  fun openServers() {
    navigator.goTo(Destination.WebDavServers(origin))
  }

  fun openImport() {
    navigator.goTo(Destination.WebDavImport(origin))
  }

  fun openBackup() {
    navigator.goTo(Destination.WebDavBackup(origin))
  }

  fun openCacheSettings() {
    navigator.goTo(Destination.WebDavCache)
  }

  /**
   * The book folders live on one server, so one server goes straight to its
   * browser. With none there is nowhere to browse yet, so the server list -
   * the place to add one - is the honest destination.
   */
  fun openBookFolders() {
    scope.launch {
      val servers = webDavLibrary.servers().first()
      when (servers.size) {
        0 -> navigator.goTo(Destination.WebDavServers(origin))
        1 -> navigator.goTo(Destination.WebDavBrowse(servers.first().id, origin))
        else -> serverPicker.value = servers
      }
    }
  }

  fun browseServer(serverId: String) {
    serverPicker.value = null
    navigator.goTo(Destination.WebDavBrowse(serverId, origin))
  }

  fun dismissServerPicker() {
    serverPicker.value = null
  }

  fun onImportParallelismRowClick() {
    importParallelismDialog.value = true
  }

  fun dismissImportParallelismDialog() {
    importParallelismDialog.value = false
  }

  fun importParallelismChanged(level: Int) {
    importParallelismDialog.value = false
    scope.launch {
      analysisParallelismStore.updateData { level }
    }
  }

  fun back() {
    navigator.goBack()
  }

  @AssistedFactory
  interface Factory {
    fun create(origin: Origin): WebDavSettingsViewModel
  }
}
