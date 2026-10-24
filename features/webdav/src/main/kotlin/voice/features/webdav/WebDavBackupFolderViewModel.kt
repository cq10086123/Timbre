package voice.features.webdav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import voice.core.common.DispatcherProvider
import voice.core.common.MainScope
import voice.core.webdav.WebDavBackupService
import voice.core.webdav.WebDavException
import voice.core.webdav.WebDavLibrary
import voice.core.webdav.WebDavResource
import voice.core.webdav.WebDavServer
import voice.navigation.Destination
import voice.navigation.Navigator
import voice.navigation.Origin

data class WebDavBackupFolderViewState(
  /** Shown while no server has been picked yet. */
  val servers: List<ServerOption> = emptyList(),
  /** Null while the server list is on screen. */
  val serverId: String? = null,
  val serverName: String? = null,
  /** Server base directory of the picked server. */
  val rootUrl: String = "",
  /** Directory currently open, or the picked folder once browsing started. */
  val path: String = "",
  val entries: List<Entry> = emptyList(),
  val loading: Boolean = true,
  val error: WebDavBrowseError? = null,
) {
  val browsing: Boolean get() = serverId != null

  /** Path below the server base directory, "/" for the base itself. */
  val relativePath: String
    get() {
      if (!browsing) return ""
      val rest = path.removePrefix(rootUrl).ifEmpty { "" }
      return rest.ifEmpty { "/" }
    }

  data class ServerOption(
    val id: String,
    val name: String,
  )

  data class Entry(
    val url: String,
    val name: String,
  )
}

/**
 * Picks the folder the shelf backup is written to and read from. The shelf
 * backup and the restore both use whatever is picked here.
 */
@AssistedInject
class WebDavBackupFolderViewModel(
  private val webDavLibrary: WebDavLibrary,
  private val backupService: WebDavBackupService,
  dispatcherProvider: DispatcherProvider,
  private val navigator: Navigator,
  @Assisted
  private val origin: Origin,
) {

  private val scope = MainScope(dispatcherProvider)

  private val state = MutableStateFlow(WebDavBackupFolderViewState())

  @Composable
  fun viewState(): WebDavBackupFolderViewState {
    val value by state.collectAsState()
    return value
  }

  init {
    scope.launch {
      val servers = webDavLibrary.servers().first()
      if (servers.isEmpty()) {
        state.update { it.copy(servers = emptyList(), loading = false) }
        return@launch
      }
      // a single server has nothing to choose, go straight to its folders
      if (servers.size == 1) {
        openServer(servers.first())
      } else {
        state.update {
          it.copy(
            servers = servers.map { server -> WebDavBackupFolderViewState.ServerOption(server.id, server.name) },
            loading = false,
          )
        }
      }
    }
  }

  fun chooseServer(serverId: String) {
    scope.launch {
      val server = webDavLibrary.server(serverId)
      if (server == null) {
        state.update { it.copy(loading = false, error = WebDavBrowseError.Generic) }
        return@launch
      }
      openServer(server)
    }
  }

  private suspend fun openServer(server: WebDavServer) {
    val current = backupService.currentLocation()
    val start = if (current?.serverId == server.id) current.folderUrl else server.baseUrl
    state.update {
      it.copy(
        serverId = server.id,
        serverName = server.name,
        rootUrl = server.baseUrl.trimEnd('/'),
        path = start,
        entries = emptyList(),
        loading = true,
        error = null,
      )
    }
    load(start)
  }

  fun load(url: String) {
    val serverId = state.value.serverId ?: return
    scope.launch {
      state.update { it.copy(loading = true, error = null) }
      try {
        val entries = webDavLibrary.list(serverId, url)
          .filter { it.isDirectory }
          .sortedBy { it.name.lowercase() }
          .map { resource: WebDavResource -> WebDavBackupFolderViewState.Entry(resource.url, resource.name) }
        state.update { it.copy(path = url.trimEnd('/'), entries = entries, loading = false) }
      } catch (e: WebDavException.Auth) {
        state.update { it.copy(loading = false, error = WebDavBrowseError.Auth) }
      } catch (_: Exception) {
        state.update { it.copy(loading = false, error = WebDavBrowseError.Generic) }
      }
    }
  }

  fun open(entry: WebDavBackupFolderViewState.Entry) {
    load(entry.url)
  }

  fun up() {
    val current = state.value
    if (!current.browsing || current.path.trimEnd('/') == current.rootUrl) return
    val parent = current.path.trimEnd('/').substringBeforeLast('/', missingDelimiterValue = "")
    if (parent.length < current.rootUrl.length) {
      load(current.rootUrl)
    } else {
      load(parent)
    }
  }

  /** Writes the open directory as the backup location and returns to the hub. */
  fun useCurrentFolder() {
    val current = state.value
    val serverId = current.serverId ?: return
    if (current.path.isBlank()) return
    scope.launch {
      backupService.setLocation(serverId = serverId, folderUrl = current.path)
      navigator.goBack()
    }
  }

  fun back() {
    navigator.goBack()
  }

  @AssistedFactory
  interface Factory {
    fun create(origin: Origin): WebDavBackupFolderViewModel
  }
}
