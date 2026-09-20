package voice.features.settings

import voice.core.data.ThemeColorScheme
import voice.core.data.ThemeMode
import java.time.LocalTime

interface SettingsListener {
  fun close()
  fun onThemeModeRowClick()
  fun onThemeColorSchemeRowClick()
  fun setThemeMode(themeMode: ThemeMode)
  fun setThemeColorScheme(themeColorScheme: ThemeColorScheme)
  fun toggleGrid()
  fun seekAmountChanged(seconds: Int)
  fun onSeekAmountRowClick()
  fun autoRewindAmountChang(seconds: Int)
  fun onAutoRewindRowClick()
  fun dismissDialog()
  fun openProjectRepository()
  fun openSupportVoice()
  fun setAutoSleepTimer(checked: Boolean)
  fun setAutoSleepTimerStart(time: LocalTime)
  fun setAutoSleepTimerEnd(time: LocalTime)
  fun toggleAnalytics()
  fun openFolderPicker()
  fun openWebDav()
  fun setAutoRefreshWifi(checked: Boolean)
  fun setBtSkipToChapter(checked: Boolean)
  fun onImportParallelismRowClick()
  fun importParallelismChanged(level: Int)
  fun onAppVersionClick()

  fun openDeveloperMenu()

  companion object {
    fun noop() = object : SettingsListener {
      override fun close() {}
      override fun onThemeModeRowClick() {}
      override fun onThemeColorSchemeRowClick() {}
      override fun setThemeMode(themeMode: ThemeMode) {}
      override fun setThemeColorScheme(themeColorScheme: ThemeColorScheme) {}
      override fun toggleGrid() {}
      override fun seekAmountChanged(seconds: Int) {}
      override fun onSeekAmountRowClick() {}
      override fun autoRewindAmountChang(seconds: Int) {}
      override fun onAutoRewindRowClick() {}
      override fun setBtSkipToChapter(checked: Boolean) {}
      override fun onImportParallelismRowClick() {}
      override fun importParallelismChanged(level: Int) {}
      override fun dismissDialog() {}
      override fun openProjectRepository() {}
      override fun openSupportVoice() {}
      override fun setAutoSleepTimer(checked: Boolean) {}
      override fun setAutoSleepTimerStart(time: LocalTime) {}
      override fun setAutoSleepTimerEnd(time: LocalTime) {}
      override fun toggleAnalytics() {}
      override fun openFolderPicker() {}
      override fun openWebDav() {}
      override fun setAutoRefreshWifi(checked: Boolean) {}
      override fun onAppVersionClick() {}
      override fun openDeveloperMenu() {}
    }
  }
}
