package voice.core.data.store

import dev.zacsweers.metro.Qualifier

@Qualifier
public annotation class OnboardingCompletedStore

@Qualifier
public annotation class CurrentBookStore

@Qualifier
public annotation class AutoRewindAmountStore

@Qualifier
public annotation class SeekTimeStore

@Qualifier
public annotation class BtSkipToChapterStore

@Qualifier
public annotation class AnalysisParallelismStore

@Qualifier
public annotation class SleepTimerPreferenceStore

@Qualifier
public annotation class GridModeStore

@Qualifier
public annotation class ThemeModeStore

@Qualifier
public annotation class ThemeColorSchemeStore

@Qualifier
public annotation class FadeOutStore

@Qualifier
public annotation class AmountOfBatteryOptimizationRequestedStore

@Qualifier
public annotation class ReviewDialogShownStore

@Qualifier
public annotation class FolderPickerMovedDialogShownStore

@Qualifier
public annotation class AnalyticsConsentStore

@Qualifier
public annotation class DeveloperMenuUnlockedStore

@Qualifier
public annotation class UpdateLastCheckStore

@Qualifier
public annotation class UpdateDismissedStore

@Qualifier
public annotation class FeatureFlagOverridesStore

/** Whether automatic library refreshes are restricted to Wi-Fi. */
@Qualifier
public annotation class WebDavAutoRefreshWifiStore
