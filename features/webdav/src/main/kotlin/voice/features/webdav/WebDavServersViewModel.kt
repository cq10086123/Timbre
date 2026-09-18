package voice.features.webdav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import voice.core.common.DispatcherProvider
import voice.core.common.MainScope
import voice.core.webdav.WebDavLibrary
import voice.core.webdav.WebDavProbeResult
import voice.core.webdav.WebDavServer
import voice.navigation.Destination
import voice.navigation.Navigator

enum class WebDavTestOutcome {
  Success,
  RangeMissing,
  AuthError,
  CertificateError,
  NetworkError,
  InvalidAddress,
  SaveError,
  MissingPassword,
}

data class WebDavServerDialog(
  val existingId: String?,
  val name: String,
  val url: String,
  val username: String,
  val password: String,
  val trustAllCertificates: Boolean,
  val testing: Boolean = false,
  val testOutcome: WebDavTestOutcome? = null,
)

data class WebDavServersViewState(
  val servers: List<WebDavServer>,
  val dialog: WebDavServerDialog?,
  val deleteCandidate: WebDavServer?,
)

@Inject
class WebDavServersViewModel(
  private val webDavLibrary: WebDavLibrary,
  dispatcherProvider: DispatcherProvider,
  private val navigator: Navigator,
) {

  private val scope = MainScope(dispatcherProvider)

  private val dialog = MutableStateFlow<WebDavServerDialog?>(null)

  private val deleteCandidate = MutableStateFlow<WebDavServer?>(null)

  @Composable
  fun viewState(): WebDavServersViewState {
    val servers by webDavLibrary.servers().collectAsState(initial = emptyList())
    val dialogState by dialog.collectAsState()
    val deleteState by deleteCandidate.collectAsState()
    return WebDavServersViewState(
      servers = servers,
      dialog = dialogState,
      deleteCandidate = deleteState,
    )
  }

  fun addServer() {
    dialog.value = WebDavServerDialog(
      existingId = null,
      name = "",
      url = "",
      username = "",
      password = "",
      trustAllCertificates = false,
    )
  }

  fun editServer(server: WebDavServer) {
    dialog.value = WebDavServerDialog(
      existingId = server.id,
      name = server.name,
      url = server.baseUrl,
      username = server.username,
      password = "",
      trustAllCertificates = server.trustAllCertificates,
    )
  }

  fun dismissDialog() {
    dialog.value = null
  }

  fun updateDialog(update: (WebDavServerDialog) -> WebDavServerDialog) {
    dialog.update { it?.let(update) }
  }

  fun requestDelete(server: WebDavServer) {
    deleteCandidate.value = server
  }

  fun dismissDelete() {
    deleteCandidate.value = null
  }

  fun confirmDelete() {
    val candidate = deleteCandidate.value ?: return
    deleteCandidate.value = null
    scope.launch {
      webDavLibrary.deleteServer(candidate.id)
    }
  }

  fun testConnection() {
    val current = dialog.value ?: return
    updateDialog {
      it.copy(testing = true, testOutcome = null)
    }
    scope.launch {
      val outcome = try {
        when (val result = webDavLibrary.testConnection(current.url, current.username, current.password, current.trustAllCertificates)) {
          is WebDavProbeResult.Success -> if (result.rangeSupported) WebDavTestOutcome.Success else WebDavTestOutcome.RangeMissing
          WebDavProbeResult.AuthError -> WebDavTestOutcome.AuthError
          WebDavProbeResult.CertificateError -> WebDavTestOutcome.CertificateError
          WebDavProbeResult.NetworkError -> WebDavTestOutcome.NetworkError
          is WebDavProbeResult.Other -> WebDavTestOutcome.InvalidAddress
        }
      } catch (_: Exception) {
        WebDavTestOutcome.InvalidAddress
      }
      updateDialog {
        it.copy(testing = false, testOutcome = outcome)
      }
    }
  }

  fun save() {
    val current = dialog.value ?: return
    scope.launch {
      val outcome = try {
        webDavLibrary.saveServer(
          name = current.name,
          baseUrl = current.url,
          username = current.username,
          password = current.password.takeIf { it.isNotBlank() },
          trustAllCertificates = current.trustAllCertificates,
          existingId = current.existingId,
        )
        null
      } catch (_: IllegalArgumentException) {
        if (current.password.isBlank() && current.existingId == null) {
          WebDavTestOutcome.MissingPassword
        } else {
          WebDavTestOutcome.InvalidAddress
        }
      } catch (_: Exception) {
        WebDavTestOutcome.SaveError
      }
      if (outcome == null) {
        dialog.value = null
      } else {
        updateDialog { it.copy(testing = false, testOutcome = outcome) }
      }
    }
  }

  fun browse(server: WebDavServer) {
    navigator.goTo(Destination.WebDavBrowse(server.id))
  }

  fun openCacheSettings() {
    navigator.goTo(Destination.WebDavCache)
  }

  fun back() {
    navigator.goBack()
  }
}
