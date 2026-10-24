package voice.features.settings.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.datastore.core.DataStore
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.launch
import voice.core.common.DispatcherProvider
import voice.core.common.MainScope
import voice.core.data.store.AutoRewindAmountStore
import voice.core.data.store.BtSkipToChapterStore
import voice.core.data.store.SeekTimeStore
import voice.core.online.OnlinePreloadSettings
import voice.core.online.OnlinePreloadSettingsStore
import voice.navigation.Navigator

@Inject
class PlayerSettingsViewModel(
  private val navigator: Navigator,
  @OnlinePreloadSettingsStore
  private val onlinePreloadSettingsStore: DataStore<OnlinePreloadSettings>,
  @SeekTimeStore
  private val seekTimeStore: DataStore<Int>,
  @AutoRewindAmountStore
  private val autoRewindAmountStore: DataStore<Int>,
  @BtSkipToChapterStore
  private val btSkipToChapterStore: DataStore<Boolean>,
  dispatcherProvider: DispatcherProvider,
) {

  private val scope = MainScope(dispatcherProvider)
  private val dialog = mutableStateOf<PlayerSettingsViewState.Dialog?>(null)

  @Composable
  fun viewState(): PlayerSettingsViewState {
    val settings by onlinePreloadSettingsStore.data.collectAsState(initial = OnlinePreloadSettings.Default)
    val seekTime by seekTimeStore.data.collectAsState(initial = 0)
    val autoRewindAmount by autoRewindAmountStore.data.collectAsState(initial = 0)
    val btSkipToChapter by btSkipToChapterStore.data.collectAsState(initial = true)
    val coerced = OnlinePreloadSettings.coerce(settings)
    return PlayerSettingsViewState(
      onlinePreloadEnabled = coerced.enabled,
      onlinePreloadTriggerSeconds = coerced.triggerSeconds,
      onlinePreloadChapterCount = coerced.chapterCount,
      onlinePreloadIntervalSeconds = coerced.intervalSeconds,
      seekTimeInSeconds = seekTime,
      autoRewindInSeconds = autoRewindAmount,
      btSkipToChapter = btSkipToChapter,
      dialog = dialog.value,
    )
  }

  fun close() {
    navigator.goBack()
  }

  fun dismissDialog() {
    dialog.value = null
  }

  fun setOnlinePreloadEnabled(checked: Boolean) {
    scope.launch {
      onlinePreloadSettingsStore.updateData { OnlinePreloadSettings.coerce(it.copy(enabled = checked)) }
    }
  }

  fun onOnlinePreloadTriggerRowClick() {
    dialog.value = PlayerSettingsViewState.Dialog.OnlinePreloadTrigger
  }

  fun onlinePreloadTriggerChanged(seconds: Int) {
    dialog.value = null
    scope.launch {
      onlinePreloadSettingsStore.updateData { OnlinePreloadSettings.coerce(it.copy(triggerSeconds = seconds)) }
    }
  }

  fun onOnlinePreloadCountRowClick() {
    dialog.value = PlayerSettingsViewState.Dialog.OnlinePreloadCount
  }

  fun onlinePreloadCountChanged(count: Int) {
    dialog.value = null
    scope.launch {
      onlinePreloadSettingsStore.updateData { OnlinePreloadSettings.coerce(it.copy(chapterCount = count)) }
    }
  }

  fun onOnlinePreloadIntervalRowClick() {
    dialog.value = PlayerSettingsViewState.Dialog.OnlinePreloadInterval
  }

  fun onlinePreloadIntervalChanged(seconds: Int) {
    dialog.value = null
    scope.launch {
      onlinePreloadSettingsStore.updateData { OnlinePreloadSettings.coerce(it.copy(intervalSeconds = seconds)) }
    }
  }

  fun seekAmountChanged(seconds: Int) {
    dialog.value = null
    scope.launch {
      seekTimeStore.updateData { seconds }
    }
  }

  fun onSeekAmountRowClick() {
    dialog.value = PlayerSettingsViewState.Dialog.SeekTime
  }

  fun autoRewindAmountChanged(seconds: Int) {
    dialog.value = null
    scope.launch {
      autoRewindAmountStore.updateData { seconds }
    }
  }

  fun onAutoRewindRowClick() {
    dialog.value = PlayerSettingsViewState.Dialog.AutoRewindAmount
  }

  fun setBtSkipToChapter(checked: Boolean) {
    scope.launch {
      btSkipToChapterStore.updateData { checked }
    }
  }
}
