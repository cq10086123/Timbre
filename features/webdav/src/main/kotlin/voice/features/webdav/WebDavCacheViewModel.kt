package voice.features.webdav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.datastore.core.DataStore
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.launch
import voice.core.common.DispatcherProvider
import voice.core.common.MainScope
import voice.core.webdav.WebDavCacheSettings
import voice.core.webdav.WebDavCacheSettingsStore
import voice.core.webdav.WebDavLibrary
import voice.navigation.Navigator

data class WebDavCacheViewState(
  val settings: WebDavCacheSettings,
  val cachedBytes: Long,
)

@Inject
class WebDavCacheViewModel(
  @WebDavCacheSettingsStore private val settingsStore: DataStore<WebDavCacheSettings>,
  private val webDavLibrary: WebDavLibrary,
  dispatcherProvider: DispatcherProvider,
  private val navigator: Navigator,
) {

  private val scope = MainScope(dispatcherProvider)

  @Composable
  fun viewState(): WebDavCacheViewState {
    val settings by settingsStore.data.collectAsState(initial = WebDavCacheSettings())
    var cachedBytes by remember { mutableLongStateOf(0L) }
    LaunchedEffect(settings.maxBytes) {
      cachedBytes = webDavLibrary.cachedBytes()
    }
    return WebDavCacheViewState(
      settings = settings,
      cachedBytes = cachedBytes,
    )
  }

  fun setMaxBytes(maxBytes: Long) {
    scope.launch {
      settingsStore.updateData { it.copy(maxBytes = maxBytes) }
    }
  }

  fun setPrefetchMode(mode: WebDavCacheSettings.PrefetchMode) {
    scope.launch {
      settingsStore.updateData { it.copy(prefetchMode = mode) }
    }
  }

  fun setPrefetchOnMetered(enabled: Boolean) {
    scope.launch {
      settingsStore.updateData { it.copy(prefetchOnMetered = enabled) }
    }
  }

  fun clearCache() {
    scope.launch {
      webDavLibrary.clearCache()
    }
  }

  fun back() {
    navigator.goBack()
  }
}
