package voice.features.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.datastore.core.DataStore
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import voice.core.common.AppInfoProvider
import voice.core.common.DispatcherProvider
import voice.core.common.MainScope
import voice.core.data.GridMode
import voice.core.data.ThemeColorScheme
import voice.core.data.ThemeMode
import voice.core.data.sleeptimer.SleepTimerPreference
import voice.core.data.store.AnalysisParallelismStore
import voice.core.data.store.AnalyticsConsentStore
import voice.core.data.store.AutoRewindAmountStore
import voice.core.data.store.BtSkipToChapterStore
import voice.core.data.store.DeveloperMenuUnlockedStore
import voice.core.data.store.GridModeStore
import voice.core.data.store.SeekTimeStore
import voice.core.data.store.SleepTimerPreferenceStore
import voice.core.data.store.ThemeColorSchemeStore
import voice.core.data.store.ThemeModeStore
import voice.core.data.store.WebDavAutoRefreshWifiStore
import voice.core.featureflag.FeatureFlag
import voice.core.featureflag.KioskModeFeatureFlagQualifier
import voice.core.online.OnlineSourceBaseUrlStore
import voice.core.online.OnlineSourceCredentialStore
import voice.core.online.OnlineSourceEnabledStore
import voice.core.online.OnlineSourceException
import voice.core.online.OnlineSourceService
import voice.core.ui.DynamicColorAvailability
import voice.core.ui.GridCount
import voice.core.update.UpdateNotifier
import voice.navigation.Destination
import voice.navigation.Navigator
import voice.navigation.Origin
import java.time.LocalTime
import voice.core.strings.R as StringsR

@Inject
class SettingsViewModel(
  @ThemeModeStore
  private val themeModeStore: DataStore<ThemeMode>,
  @ThemeColorSchemeStore
  private val themeColorSchemeStore: DataStore<ThemeColorScheme>,
  @AutoRewindAmountStore
  private val autoRewindAmountStore: DataStore<Int>,
  @SeekTimeStore
  private val seekTimeStore: DataStore<Int>,
  private val navigator: Navigator,
  private val appInfoProvider: AppInfoProvider,
  @GridModeStore
  private val gridModeStore: DataStore<GridMode>,
  @SleepTimerPreferenceStore
  private val sleepTimerPreferenceStore: DataStore<SleepTimerPreference>,
  @AnalyticsConsentStore
  private val analyticsConsentStore: DataStore<Boolean>,
  private val gridCount: GridCount,
  @KioskModeFeatureFlagQualifier
  private val kioskModeFeatureFlag: FeatureFlag<Boolean>,
  @DeveloperMenuUnlockedStore
  private val developerMenuUnlockedStore: DataStore<Boolean>,
  @WebDavAutoRefreshWifiStore
  private val autoRefreshWifiStore: DataStore<Boolean>,
  @BtSkipToChapterStore
  private val btSkipToChapterStore: DataStore<Boolean>,
  @AnalysisParallelismStore
  private val analysisParallelismStore: DataStore<Int>,
  @OnlineSourceEnabledStore
  private val onlineSourceEnabledStore: DataStore<Boolean>,
  @OnlineSourceBaseUrlStore
  private val onlineSourceBaseUrlStore: DataStore<String>,
  @OnlineSourceCredentialStore
  private val onlineSourceCredentialStore: DataStore<String>,
  private val onlineSourceService: OnlineSourceService,
  private val dynamicColorAvailability: DynamicColorAvailability,
  private val updateNotifier: UpdateNotifier,
  dispatcherProvider: DispatcherProvider,
) : SettingsListener {

  private val mainScope = MainScope(dispatcherProvider)
  internal val viewEffects: SharedFlow<SettingsViewEffect>
    field = MutableSharedFlow<SettingsViewEffect>(extraBufferCapacity = 1)
  private val dialog = mutableStateOf<SettingsViewState.Dialog?>(null)
  private val onlineSourceVerifyState = mutableStateOf(false)
  private val onlineSourceVerifyError = mutableStateOf<Int?>(null)
  private var appVersionTapCount = 0

  @Composable
  fun viewState(): SettingsViewState {
    val themeMode by remember { themeModeStore.data }.collectAsState(initial = ThemeMode.FollowSystem)
    val themeColorScheme by remember { themeColorSchemeStore.data }.collectAsState(initial = ThemeColorScheme.VoiceBlue)
    val autoRewindAmount by remember { autoRewindAmountStore.data }.collectAsState(initial = 0)
    val seekTime by remember { seekTimeStore.data }.collectAsState(initial = 0)
    val gridMode by remember { gridModeStore.data }.collectAsState(initial = GridMode.GRID)
    val autoSleepTimer by remember { sleepTimerPreferenceStore.data }.collectAsState(
      initial = SleepTimerPreference.Default,
    )
    val analyticsEnabled by remember { analyticsConsentStore.data }.collectAsState(initial = false)
    val kioskMode = remember {
      kioskModeFeatureFlag.get()
    }
    val showDeveloperMenu by remember { developerMenuUnlockedStore.data }.collectAsState(initial = false)
    val autoRefreshWifi by remember { autoRefreshWifiStore.data }.collectAsState(initial = true)
    val btSkipToChapter by remember { btSkipToChapterStore.data }.collectAsState(initial = true)
    val importParallelism by remember { analysisParallelismStore.data }.collectAsState(initial = 1)
    val onlineSourceEnabled by remember { onlineSourceEnabledStore.data }.collectAsState(initial = false)
    val onlineSourceBaseUrl by remember { onlineSourceBaseUrlStore.data }.collectAsState(initial = "")
    val onlineSourceCredential by remember { onlineSourceCredentialStore.data }.collectAsState(initial = "")
    val onlineSourceVerifying = onlineSourceVerifyState.value
    val onlineSourceVerifyError = onlineSourceVerifyError.value
    val showThemeColorSchemePref = remember {
      dynamicColorAvailability.isSupported()
    }
    return SettingsViewState(
      themeMode = themeMode,
      themeColorScheme = themeColorScheme,
      showThemeColorSchemePref = showThemeColorSchemePref,
      seekTimeInSeconds = seekTime,
      autoRewindInSeconds = autoRewindAmount,
      dialog = dialog.value,
      appVersion = appInfoProvider.versionName,
      updateAvailable = updateNotifier.update.collectAsState().value?.versionName,
      useGrid = when (gridMode) {
        GridMode.LIST -> false
        GridMode.GRID -> true
        GridMode.FOLLOW_DEVICE -> gridCount.useGridAsDefault()
      },
      autoSleepTimer = SettingsViewState.AutoSleepTimerViewState(
        enabled = autoSleepTimer.autoSleepTimerEnabled,
        startTime = autoSleepTimer.autoSleepStartTime,
        endTime = autoSleepTimer.autoSleepEndTime,
      ),
      analyticsEnabled = analyticsEnabled,
      showAnalyticSetting = appInfoProvider.analyticsIncluded,
      showDeveloperMenu = showDeveloperMenu,
      autoRefreshWifi = autoRefreshWifi,
      btSkipToChapter = btSkipToChapter,
      importParallelism = importParallelism,
      onlineSourceEnabled = onlineSourceEnabled,
      onlineSourceBaseUrl = onlineSourceBaseUrl,
      onlineSourceCredential = onlineSourceCredential,
      onlineSourceVerifying = onlineSourceVerifying,
      onlineSourceVerifyError = onlineSourceVerifyError,
      showSupportDevelopment = appInfoProvider.supportDevelopmentIncluded,
      kioskMode = kioskMode,
    )
  }

  override fun close() {
    navigator.goBack()
  }

  override fun onThemeModeRowClick() {
    dialog.value = SettingsViewState.Dialog.Theme
  }

  override fun onThemeColorSchemeRowClick() {
    dialog.value = SettingsViewState.Dialog.ColorScheme
  }

  override fun setThemeMode(themeMode: ThemeMode) {
    mainScope.launch {
      themeModeStore.updateData { themeMode }
    }
    dialog.value = null
  }

  override fun setThemeColorScheme(themeColorScheme: ThemeColorScheme) {
    mainScope.launch {
      themeColorSchemeStore.updateData { themeColorScheme }
    }
    dialog.value = null
  }

  override fun toggleGrid() {
    mainScope.launch {
      gridModeStore.updateData { currentMode ->
        when (currentMode) {
          GridMode.LIST -> GridMode.GRID
          GridMode.GRID -> GridMode.LIST
          GridMode.FOLLOW_DEVICE -> if (gridCount.useGridAsDefault()) {
            GridMode.LIST
          } else {
            GridMode.GRID
          }
        }
      }
    }
  }

  override fun seekAmountChanged(seconds: Int) {
    mainScope.launch {
      seekTimeStore.updateData { seconds }
    }
  }

  override fun onSeekAmountRowClick() {
    dialog.value = SettingsViewState.Dialog.SeekTime
  }

  override fun autoRewindAmountChang(seconds: Int) {
    mainScope.launch {
      autoRewindAmountStore.updateData { seconds }
    }
  }

  override fun onAutoRewindRowClick() {
    dialog.value = SettingsViewState.Dialog.AutoRewindAmount
  }

  override fun dismissDialog() {
    dialog.value = null
  }

  override fun openProjectRepository() {
    navigator.goTo(Destination.Website("https://github.com/cq10086123/Timbre"))
  }

  override fun openSupportVoice() {
    navigator.goTo(Destination.SupportVoice)
  }

  override fun openFolderPicker() {
    navigator.goTo(Destination.FolderPicker)
  }

  override fun openWebDav() {
    navigator.goTo(Destination.WebDavServers(Origin.Default))
  }

  override fun setAutoRefreshWifi(checked: Boolean) {
    mainScope.launch {
      autoRefreshWifiStore.updateData { checked }
    }
  }

  override fun setBtSkipToChapter(checked: Boolean) {
    mainScope.launch {
      btSkipToChapterStore.updateData { checked }
    }
  }

  override fun setOnlineSourceEnabled(checked: Boolean) {
    mainScope.launch {
      onlineSourceEnabledStore.updateData { checked }
    }
  }

  override fun onOnlineSourceBaseUrlRowClick() {
    dialog.value = SettingsViewState.Dialog.OnlineSourceBaseUrl
  }

  override fun onOnlineSourceCredentialRowClick() {
    dialog.value = SettingsViewState.Dialog.OnlineSourceCredential
  }

  override fun onlineSourceBaseUrlChanged(value: String) {
    val base = value.trim().removeSuffix("/")
    if (!base.startsWith("http://") && !base.startsWith("https://")) {
      onlineSourceVerifyError.value = verifyErrorText(needBaseUrl = false, invalidUrl = true)
      return
    }
    mainScope.launch {
      val credential = onlineSourceCredentialStore.data.first()
      if (credential.isBlank()) {
        // nothing to validate yet: accept and let the key dialog verify both
        onlineSourceBaseUrlStore.updateData { base }
        onlineSourceService.invalidateToken()
        dialog.value = null
      } else {
        saveVerified(base = base, credential = credential, keepCredential = true)
      }
    }
  }

  override fun onlineSourceCredentialChanged(value: String) {
    mainScope.launch {
      val base = onlineSourceBaseUrlStore.data.first()
      if (base.isBlank()) {
        onlineSourceVerifyError.value = verifyErrorText(needBaseUrl = true, invalidUrl = false)
        return@launch
      }
      saveVerified(base = base, credential = value.trim(), keepCredential = false)
    }
  }

  private suspend fun saveVerified(
    base: String,
    credential: String,
    keepCredential: Boolean,
  ) {
    onlineSourceVerifyState.value = true
    onlineSourceVerifyError.value = null
    try {
      onlineSourceService.verify(base, credential).let { /* token persisted inside the service */ }
      onlineSourceBaseUrlStore.updateData { base }
      if (!keepCredential) {
        onlineSourceCredentialStore.updateData { credential }
      }
      dialog.value = null
    } catch (e: OnlineSourceException) {
      onlineSourceVerifyError.value = verifyErrorText(needBaseUrl = false, invalidUrl = false)
    } catch (e: Exception) {
      onlineSourceVerifyError.value = verifyErrorText(needBaseUrl = false, invalidUrl = false)
    } finally {
      onlineSourceVerifyState.value = false
    }
  }

  private fun verifyErrorText(
    needBaseUrl: Boolean,
    invalidUrl: Boolean,
  ): Int? = when {
    needBaseUrl -> StringsR.string.settings_online_source_error_need_base_url
    invalidUrl -> StringsR.string.settings_online_source_error_invalid_url
    else -> StringsR.string.settings_online_source_error_invalid
  }

  override fun onImportParallelismRowClick() {
    dialog.value = SettingsViewState.Dialog.ImportParallelism
  }

  override fun importParallelismChanged(level: Int) {
    dialog.value = null
    mainScope.launch {
      analysisParallelismStore.updateData { level }
    }
  }

  override fun setAutoSleepTimer(checked: Boolean) {
    mainScope.launch {
      sleepTimerPreferenceStore.updateData { currentPrefs ->
        currentPrefs.copy(autoSleepTimerEnabled = checked)
      }
    }
  }

  override fun setAutoSleepTimerStart(time: LocalTime) {
    mainScope.launch {
      sleepTimerPreferenceStore.updateData { currentPrefs ->
        currentPrefs.copy(autoSleepStartTime = time)
      }
    }
  }

  override fun setAutoSleepTimerEnd(time: LocalTime) {
    mainScope.launch {
      sleepTimerPreferenceStore.updateData { currentPrefs ->
        currentPrefs.copy(autoSleepEndTime = time)
      }
    }
  }

  override fun toggleAnalytics() {
    mainScope.launch {
      analyticsConsentStore.updateData { !it }
    }
  }

  override fun onAppVersionClick() {
    mainScope.launch {
      if (updateNotifier.update.value != null) {
        navigator.goTo(Destination.Website(UpdateNotifier.RELEASE_PAGE_URL))
        return@launch
      }
      if (developerMenuUnlockedStore.data.first()) {
        return@launch
      }
      if (++appVersionTapCount >= 13) {
        developerMenuUnlockedStore.updateData { true }
        viewEffects.emit(SettingsViewEffect.DeveloperMenuUnlocked)
      }
    }
  }

  override fun openDeveloperMenu() {
    navigator.goTo(Destination.DeveloperSettings)
  }
}
