package voice.features.webdav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import voice.core.common.DispatcherProvider
import voice.core.common.MainScope
import voice.core.scanner.MediaScanTrigger
import voice.core.webdav.WebDavBookSource
import voice.core.webdav.WebDavLibrary
import voice.core.webdav.WebDavResource
import voice.navigation.Destination
import voice.navigation.Navigator

data class WebDavBrowseViewState(
  val serverName: String,
  val path: String,
  val entries: List<WebDavEntry>,
  val loading: Boolean,
  val error: Boolean,
) {
  data class WebDavEntry(
    val url: String,
    val name: String,
    val isDirectory: Boolean,
  )
}

@AssistedInject
class WebDavBrowseViewModel(
  private val webDavLibrary: WebDavLibrary,
  private val mediaScanTrigger: MediaScanTrigger,
  dispatcherProvider: DispatcherProvider,
  private val navigator: Navigator,
  @Assisted
  private val serverId: String,
) {

  private val scope = MainScope(dispatcherProvider)

  private val state = MutableStateFlow(
    WebDavBrowseViewState(
      serverName = "",
      path = "",
      entries = emptyList(),
      loading = true,
      error = false,
    ),
  )

  @Composable
  fun viewState(): WebDavBrowseViewState {
    val value by state.collectAsState()
    return value
  }

  init {
    scope.launch {
      val server = webDavLibrary.server(serverId)
      if (server == null) {
        state.update { it.copy(error = true, loading = false) }
        return@launch
      }
      state.update { it.copy(serverName = server.name) }
      load(server.baseUrl)
    }
  }

  fun load(url: String) {
    scope.launch {
      state.update { it.copy(loading = true, error = false) }
      try {
        val entries = webDavLibrary.list(serverId, url)
          .sortedWith(compareByDescending<WebDavResource> { it.isDirectory }.thenBy { it.name.lowercase() })
        state.update {
          it.copy(
            path = url.trimEnd('/'),
            entries = entries.map { resource ->
              WebDavBrowseViewState.WebDavEntry(resource.url, resource.name, resource.isDirectory)
            },
            loading = false,
          )
        }
      } catch (_: Exception) {
        state.update { it.copy(loading = false, error = true) }
      }
    }
  }

  fun open(entry: WebDavBrowseViewState.WebDavEntry) {
    if (entry.isDirectory) {
      load(entry.url)
    }
  }

  fun up() {
    val current = state.value.path
    if (current.isBlank()) return
    val parent = current.substringBeforeLast('/', missingDelimiterValue = "")
    // don't navigate above the server root
    if (!parent.startsWith("http://") && !parent.startsWith("https://")) return
    load(parent)
  }

  fun addAsBook() {
    add(WebDavBookSource.Mode.SingleBook)
  }

  fun addAsLibraryRoot() {
    add(WebDavBookSource.Mode.LibraryRoot)
  }

  private fun add(mode: WebDavBookSource.Mode) {
    val path = state.value.path
    if (path.isBlank()) return
    val name = path.substringAfterLast('/')
    scope.launch {
      webDavLibrary.addBook(
        serverId = serverId,
        url = path,
        displayName = name,
        mode = mode,
      )
      mediaScanTrigger.scan(restartIfScanning = true)
      navigator.setRoot(Destination.BookOverview)
    }
  }

  fun back() {
    navigator.goBack()
  }

  @AssistedFactory
  interface Factory {
    fun create(serverId: String): WebDavBrowseViewModel
  }
}
