package voice.features.settings

import voice.core.data.ThemeColorScheme
import voice.core.data.ThemeMode
import java.time.LocalTime

data class SettingsViewState(
  val themeMode: ThemeMode,
  val themeColorScheme: ThemeColorScheme,
  val showThemeColorSchemePref: Boolean,
  val seekTimeInSeconds: Int,
  val autoRewindInSeconds: Int,
  val appVersion: String,
  val dialog: Dialog?,
  val useGrid: Boolean,
  val autoSleepTimer: AutoSleepTimerViewState,
  val showAnalyticSetting: Boolean,
  val analyticsEnabled: Boolean,
  val showDeveloperMenu: Boolean,
  val autoRefreshWifi: Boolean,
  val btSkipToChapter: Boolean,
  val importParallelism: Int,
  val onlineSourceEnabled: Boolean,
  val onlineSourceBaseUrl: String,
  val onlineSourceCredential: String,
  val onlineSourceVerifying: Boolean = false,
  val onlineSourceVerifyError: Int? = null,
  val showSupportDevelopment: Boolean,
  val kioskMode: Boolean,
  val updateAvailable: String? = null,
) {

  enum class Dialog {
    AutoRewindAmount,
    SeekTime,
    ImportParallelism,
    OnlineSourceBaseUrl,
    OnlineSourceCredential,
    Theme,
    ColorScheme,
  }

  companion object {
    fun preview(): SettingsViewState {
      return SettingsViewState(
        themeMode = ThemeMode.FollowSystem,
        themeColorScheme = ThemeColorScheme.VoiceBlue,
        showThemeColorSchemePref = true,
        seekTimeInSeconds = 42,
        autoRewindInSeconds = 12,
        btSkipToChapter = true,
        importParallelism = 1,
        onlineSourceEnabled = false,
        onlineSourceBaseUrl = "",
        onlineSourceCredential = "",
        onlineSourceVerifying = false,
        onlineSourceVerifyError = null,
        dialog = null,
        appVersion = "1.2.3",
        useGrid = true,
        autoSleepTimer = AutoSleepTimerViewState.preview(),
        analyticsEnabled = false,
        showAnalyticSetting = true,
        showDeveloperMenu = true,
        autoRefreshWifi = true,
        showSupportDevelopment = true,
        kioskMode = false,
      )
    }
  }

  data class AutoSleepTimerViewState(
    val enabled: Boolean,
    val startTime: LocalTime,
    val endTime: LocalTime,
  ) {
    companion object {
      fun preview(): AutoSleepTimerViewState {
        return AutoSleepTimerViewState(
          enabled = false,
          startTime = LocalTime.of(22, 0),
          endTime = LocalTime.of(6, 0),
        )
      }
    }
  }
}
