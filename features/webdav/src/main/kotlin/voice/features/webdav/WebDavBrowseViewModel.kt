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
import voice.core.webdav.WebDavException
import voice.core.webdav.WebDavLibrary
import voice.core.webdav.WebDavResource
import voice.navigation.Destination
import voice.navigation.Navigator
import voice.navigation.Origin

/** Why the browse screen shows an error. */
enum class WebDavBrowseError {
  /** The credentials did not work - e.g. the stored password cannot be
   *  decrypted after a reinstall, or the password changed on the server. */
  Auth,

  /** Anything else (server unreachable, malformed response, ...). */
  Generic,
}

data class WebDavBrowseViewState(
  val serverName: String,
  val path: String,
  val entries: List<WebDavEntry>,
  val loading: Boolean,
  val error: WebDavBrowseError?,
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
  @Assisted
  private val origin: Origin,
) {

  private val scope = MainScope(dispatcherProvider)

  private val state = MutableStateFlow(
    WebDavBrowseViewState(
      serverName = "",
      path = "",
      entries = emptyList(),
      loading = true,
      error = null,
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
        state.update { it.copy(error = WebDavBrowseError.Generic, loading = false) }
        return@launch
      }
      state.update { it.copy(serverName = server.name) }
      load(server.baseUrl)
    }
  }

  fun load(url: String) {
    scope.launch {
      state.update { it.copy(loading = true, error = null) }
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
      } catch (e: WebDavException.Auth) {
        state.update { it.copy(loading = false, error = WebDavBrowseError.Auth) }
      } catch (_: Exception) {
        state.update { it.copy(loading = false, error = WebDavBrowseError.Generic) }
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
      // an import during onboarding has to finish the onboarding flow (which
      // marks it completed), otherwise the next app start would show it again
      navigator.setRoot(
        when (origin) {
          Origin.Default -> Destination.BookOverview
          Origin.Onboarding -> Destination.OnboardingCompletion
        },
      )
    }
  }

  fun back() {
    navigator.goBack()
  }

  @AssistedFactory
  interface Factory {
    fun create(
      serverId: String,
      origin: Origin,
    ): WebDavBrowseViewModel
  }
}
