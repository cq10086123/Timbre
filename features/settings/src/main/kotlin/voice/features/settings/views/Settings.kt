package voice.features.settings.views

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.retain.retain
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavEntry
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides
import voice.core.common.rootGraphAs
import voice.core.ui.VoiceTheme
import voice.core.ui.icons.VoiceIcons
import voice.features.settings.SettingsListener
import voice.features.settings.SettingsViewEffect
import voice.features.settings.SettingsViewModel
import voice.features.settings.SettingsViewState
import voice.features.settings.views.sleeptimer.AutoSleepTimerCard
import voice.navigation.Destination
import voice.navigation.NavEntryProvider
import voice.core.strings.R as StringsR

@Composable
@Preview
private fun SettingsPreview() {
  VoiceTheme {
    Settings(
      SettingsViewState.preview(),
      SettingsListener.noop(),
    )
  }
}

@Composable
private fun Settings(
  viewState: SettingsViewState,
  listener: SettingsListener,
  snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
  val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
  Scaffold(
    modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
    snackbarHost = {
      SnackbarHost(hostState = snackbarHostState)
    },
    topBar = {
      TopAppBar(
        scrollBehavior = scrollBehavior,
        title = {
          Text(stringResource(StringsR.string.settings_action_open))
        },
        navigationIcon = {
          IconButton(
            onClick = {
              listener.close()
            },
          ) {
            Icon(
              imageVector = VoiceIcons.Close,
              contentDescription = stringResource(StringsR.string.common_action_close),
            )
          }
        },
      )
    },
  ) { contentPadding ->
    LazyColumn(contentPadding = contentPadding) {
      if (viewState.showDeveloperMenu && !viewState.kioskMode) {
        item {
          DeveloperMenuItem(
            onClick = listener::openDeveloperMenu,
          )
        }
      }
      item {
        ListItem(
          modifier = Modifier.clickable { listener.openFolderPicker() },
          leadingContent = {
            Icon(
              imageVector = VoiceIcons.Book,
              contentDescription = stringResource(StringsR.string.library_folders_title),
            )
          },
          supportingContent = {
            Text(stringResource(StringsR.string.settings_library_folders_summary))
          },
        ) {
          Text(stringResource(StringsR.string.library_folders_title))
        }
      }
      item {
        ListItem(
          modifier = Modifier.clickable { listener.openWebDav() },
          leadingContent = {
            Icon(
              imageVector = VoiceIcons.Language,
              contentDescription = stringResource(StringsR.string.webdav_title),
            )
          },
          supportingContent = {
            Text(stringResource(StringsR.string.webdav_settings_summary))
          },
        ) {
          Text(stringResource(StringsR.string.webdav_title))
        }
      }
      item {
        ListItem(
          modifier = Modifier.clickable { listener.setOnlineSourceEnabled(!viewState.onlineSourceEnabled) },
          leadingContent = {
            Icon(
              imageVector = VoiceIcons.Language,
              contentDescription = stringResource(StringsR.string.settings_online_source_title),
            )
          },
          supportingContent = {
            Text(stringResource(StringsR.string.settings_online_source_summary))
          },
          trailingContent = {
            Switch(
              checked = viewState.onlineSourceEnabled,
              onCheckedChange = listener::setOnlineSourceEnabled,
            )
          },
        ) {
          Text(stringResource(StringsR.string.settings_online_source_title))
        }
      }
      if (viewState.onlineSourceEnabled) {
        item {
          ListItem(
            modifier = Modifier.clickable { listener.onOnlineSourceBaseUrlRowClick() },
            leadingContent = {
              Icon(
                imageVector = VoiceIcons.Language,
                contentDescription = stringResource(StringsR.string.settings_online_source_base_url_title),
              )
            },
            supportingContent = {
              Text(
                text = viewState.onlineSourceBaseUrl.ifBlank {
                  stringResource(StringsR.string.settings_online_source_not_set)
                },
              )
            },
          ) {
            Text(stringResource(StringsR.string.settings_online_source_base_url_title))
          }
        }
        item {
          ListItem(
            modifier = Modifier.clickable { listener.onOnlineSourceCredentialRowClick() },
            leadingContent = {
              Icon(
                imageVector = VoiceIcons.LockOpen,
                contentDescription = stringResource(StringsR.string.settings_online_source_credential_title),
              )
            },
            supportingContent = {
              Text(
                text = if (viewState.onlineSourceCredential.isBlank()) {
                  stringResource(StringsR.string.settings_online_source_not_set)
                } else {
                  "••••••••"
                },
              )
            },
          ) {
            Text(stringResource(StringsR.string.settings_online_source_credential_title))
          }
        }
        item {
          ListItem(
            modifier = Modifier.clickable { listener.onSourceManagerClick() },
            leadingContent = {
              Icon(
                imageVector = VoiceIcons.Book,
                contentDescription = stringResource(StringsR.string.settings_online_source_jdr_title),
              )
            },
            supportingContent = {
              Text(stringResource(StringsR.string.settings_online_source_jdr_summary))
            },
          ) {
            Text(stringResource(StringsR.string.settings_online_source_jdr_title))
          }
        }
      }
      item {
        ListItem(
          modifier = Modifier.clickable { listener.setAutoRefreshWifi(!viewState.autoRefreshWifi) },
          leadingContent = {
            Icon(
              imageVector = VoiceIcons.Language,
              contentDescription = stringResource(StringsR.string.settings_library_auto_refresh_wifi_title),
            )
          },
          supportingContent = {
            Text(stringResource(StringsR.string.settings_library_auto_refresh_wifi_summary))
          },
          trailingContent = {
            Switch(
              checked = viewState.autoRefreshWifi,
              onCheckedChange = listener::setAutoRefreshWifi,
            )
          },
        ) {
          Text(stringResource(StringsR.string.settings_library_auto_refresh_wifi_title))
        }
      }
      item {
        ThemeModeRow(viewState.themeMode, listener::onThemeModeRowClick)
      }
      if (viewState.showThemeColorSchemePref) {
        item {
          ThemeColorSchemeRow(viewState.themeColorScheme, listener::onThemeColorSchemeRowClick)
        }
      }
      if (viewState.showAnalyticSetting && !viewState.kioskMode) {
        item {
          AnalyticsRow(analyticsEnabled = viewState.analyticsEnabled, toggle = listener::toggleAnalytics)
        }
      }
      item {
        ListItem(
          modifier = Modifier.clickable { listener.toggleGrid() },
          leadingContent = {
            val icon = if (viewState.useGrid) {
              VoiceIcons.GridView
            } else {
              VoiceIcons.ViewList
            }
            Icon(
              imageVector = icon,
              contentDescription = stringResource(StringsR.string.settings_library_use_grid_title),
            )
          },
          trailingContent = {
            Switch(
              checked = viewState.useGrid,
              onCheckedChange = {
                listener.toggleGrid()
              },
            )
          },
        ) {
          Text(stringResource(StringsR.string.settings_library_use_grid_title))
        }
      }

      item {
        SeekTimeRow(viewState.seekTimeInSeconds) {
          listener.onSeekAmountRowClick()
        }
      }

      item {
        AutoRewindRow(viewState.autoRewindInSeconds) {
          listener.onAutoRewindRowClick()
        }
      }

      item {
        ListItem(
          modifier = Modifier.clickable { listener.onImportParallelismRowClick() },
          leadingContent = {
            Icon(
              imageVector = VoiceIcons.Speed,
              contentDescription = stringResource(StringsR.string.settings_playback_import_parallelism_title),
            )
          },
          supportingContent = {
            Text(stringResource(StringsR.string.settings_playback_import_parallelism_summary))
          },
          trailingContent = {
            Text(
              text = stringResource(StringsR.string.settings_playback_import_parallelism_value, viewState.importParallelism),
              style = MaterialTheme.typography.bodyLarge,
            )
          },
        ) {
          Text(stringResource(StringsR.string.settings_playback_import_parallelism_title))
        }
      }

      item {
        ListItem(
          modifier = Modifier.clickable { listener.setBtSkipToChapter(!viewState.btSkipToChapter) },
          leadingContent = {
            Icon(
              imageVector = VoiceIcons.FastRewind,
              contentDescription = stringResource(StringsR.string.settings_playback_bt_skip_chapter_title),
            )
          },
          supportingContent = {
            Text(stringResource(StringsR.string.settings_playback_bt_skip_chapter_summary))
          },
          trailingContent = {
            Switch(
              checked = viewState.btSkipToChapter,
              onCheckedChange = listener::setBtSkipToChapter,
            )
          },
        ) {
          Text(stringResource(StringsR.string.settings_playback_bt_skip_chapter_title))
        }
      }

      item {
        AutoSleepTimerCard(viewState.autoSleepTimer, listener)
      }

      if (viewState.showSupportDevelopment) {
        item {
          ListItem(
            modifier = Modifier.clickable { listener.openSupportVoice() },
            leadingContent = {
              Icon(
                imageVector = VoiceIcons.Favorite,
                contentDescription = stringResource(StringsR.string.settings_support_support_voice_title),
                tint = MaterialTheme.colorScheme.primary,
              )
            },
            supportingContent = {
              Text(stringResource(StringsR.string.settings_support_support_voice_summary))
            },
          ) {
            Text(stringResource(StringsR.string.settings_support_support_voice_title))
          }
        }
      }

      item {
        ListItem(
          modifier = Modifier.clickable { listener.openProjectRepository() },
          leadingContent = {
            Icon(
              imageVector = VoiceIcons.Github,
              contentDescription = stringResource(StringsR.string.settings_github_title),
            )
          },
        ) {
          Text(stringResource(StringsR.string.settings_github_title))
        }
      }
      item {
        AppVersion(
          appVersion = viewState.appVersion,
          updateAvailable = viewState.updateAvailable,
          onClick = listener::onAppVersionClick,
        )
      }
      if (viewState.kioskMode) {
        if (viewState.showAnalyticSetting) {
          item {
            AnalyticsRow(analyticsEnabled = viewState.analyticsEnabled, toggle = listener::toggleAnalytics)
          }
        }
        if (viewState.showDeveloperMenu) {
          item {
            DeveloperMenuItem(
              onClick = listener::openDeveloperMenu,
            )
          }
        }
      }
    }
    Dialog(viewState, listener)
  }
}

@Composable
private fun AnalyticsRow(
  analyticsEnabled: Boolean,
  toggle: () -> Unit,
) {
  ListItem(
    modifier = Modifier.clickable { toggle() },
    leadingContent = {
      Icon(
        imageVector = VoiceIcons.Analytics,
        contentDescription = null,
      )
    },
    supportingContent = {
      Text(text = stringResource(StringsR.string.settings_analytics_consent_description))
    },
    trailingContent = {
      Switch(
        checked = analyticsEnabled,
        onCheckedChange = { toggle() },
      )
    },
  ) {
    Text(text = stringResource(StringsR.string.settings_analytics_consent_title))
  }
}

@ContributesTo(AppScope::class)
interface SettingsGraph {
  val settingsViewModel: SettingsViewModel
}

@ContributesTo(AppScope::class)
interface SettingsProvider {

  @Provides
  @IntoSet
  fun settingsNavEntryProvider(): NavEntryProvider<*> = NavEntryProvider<Destination.Settings> { key ->
    NavEntry(key) {
      Settings()
    }
  }
}

@Composable
fun Settings() {
  val viewModel = retain<SettingsViewModel> { rootGraphAs<SettingsGraph>().settingsViewModel }
  val snackbarHostState = remember { SnackbarHostState() }
  val viewState = viewModel.viewState()
  val currentDeveloperMenuUnlockedMessage = rememberUpdatedState("Developer Menu unlocked")
  LaunchedEffect(viewModel) {
    viewModel.viewEffects.collect { viewEffect ->
      when (viewEffect) {
        SettingsViewEffect.DeveloperMenuUnlocked -> {
          snackbarHostState.showSnackbar(currentDeveloperMenuUnlockedMessage.value)
        }
      }
    }
  }
  Settings(viewState, viewModel, snackbarHostState)
}

@Composable
private fun Dialog(
  viewState: SettingsViewState,
  listener: SettingsListener,
) {
  val dialog = viewState.dialog ?: return
  when (dialog) {
    SettingsViewState.Dialog.AutoRewindAmount -> {
      AutoRewindAmountDialog(
        currentSeconds = viewState.autoRewindInSeconds,
        onSecondsConfirm = listener::autoRewindAmountChang,
        onDismiss = listener::dismissDialog,
      )
    }
    SettingsViewState.Dialog.OnlineSourceBaseUrl -> {
      OnlineSourceTextDialog(
        title = stringResource(StringsR.string.settings_online_source_base_url_title),
        hint = stringResource(StringsR.string.settings_online_source_base_url_hint),
        currentValue = viewState.onlineSourceBaseUrl,
        maskInput = false,
        verifying = viewState.onlineSourceVerifying,
        errorResId = viewState.onlineSourceVerifyError,
        onConfirm = listener::onlineSourceBaseUrlChanged,
        onDismiss = listener::dismissDialog,
      )
    }
    SettingsViewState.Dialog.OnlineSourceCredential -> {
      OnlineSourceTextDialog(
        title = stringResource(StringsR.string.settings_online_source_credential_title),
        hint = stringResource(StringsR.string.settings_online_source_credential_hint),
        currentValue = viewState.onlineSourceCredential,
        maskInput = true,
        verifying = viewState.onlineSourceVerifying,
        errorResId = viewState.onlineSourceVerifyError,
        onConfirm = listener::onlineSourceCredentialChanged,
        onDismiss = listener::dismissDialog,
      )
    }
    SettingsViewState.Dialog.ImportParallelism -> {
      ImportParallelismDialog(
        currentLevel = viewState.importParallelism,
        onLevelSelect = listener::importParallelismChanged,
        onDismiss = listener::dismissDialog,
      )
    }
    SettingsViewState.Dialog.SeekTime -> {
      SeekAmountDialog(
        currentSeconds = viewState.seekTimeInSeconds,
        onSecondsConfirm = listener::seekAmountChanged,
        onDismiss = listener::dismissDialog,
      )
    }
    SettingsViewState.Dialog.Theme -> {
      ThemeModeDialog(
        selectedThemeMode = viewState.themeMode,
        onThemeModeSelect = listener::setThemeMode,
        onDismiss = listener::dismissDialog,
      )
    }
    SettingsViewState.Dialog.ColorScheme -> {
      ThemeColorSchemeDialog(
        selectedThemeColorScheme = viewState.themeColorScheme,
        onThemeColorSchemeSelect = listener::setThemeColorScheme,
        onDismiss = listener::dismissDialog,
      )
    }
  }
}

@Composable
internal fun ImportParallelismDialog(
  currentLevel: Int,
  onLevelSelect: (Int) -> Unit,
  onDismiss: () -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = {
      Text(stringResource(StringsR.string.settings_playback_import_parallelism_title))
    },
    text = {
      Column {
        Text(
          modifier = Modifier.padding(bottom = 8.dp),
          text = stringResource(StringsR.string.settings_playback_import_parallelism_summary),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        (1..3).forEach { level ->
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .clickable { onLevelSelect(level) },
            verticalAlignment = Alignment.CenterVertically,
          ) {
            RadioButton(
              selected = level == currentLevel,
              onClick = { onLevelSelect(level) },
            )
            Text(
              text = stringResource(StringsR.string.settings_playback_import_parallelism_option, level),
              style = MaterialTheme.typography.bodyLarge,
            )
          }
        }
      }
    },
    confirmButton = {},
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text(stringResource(StringsR.string.common_dialog_cancel))
      }
    },
  )
}

@Composable
private fun OnlineSourceTextDialog(
  title: String,
  hint: String,
  currentValue: String,
  maskInput: Boolean,
  verifying: Boolean,
  errorResId: Int?,
  onConfirm: (String) -> Unit,
  onDismiss: () -> Unit,
) {
  var text by remember(currentValue) { mutableStateOf(currentValue) }
  AlertDialog(
    onDismissRequest = { if (!verifying) onDismiss() },
    title = { Text(title) },
    text = {
      Column {
        OutlinedTextField(
          modifier = Modifier.fillMaxWidth(),
          value = text,
          onValueChange = { text = it },
          placeholder = { Text(hint) },
          singleLine = true,
          enabled = !verifying,
          isError = errorResId != null,
          visualTransformation = if (maskInput) PasswordVisualTransformation() else VisualTransformation.None,
        )
        if (verifying) {
          Row(
            modifier = Modifier.padding(top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Text(
              modifier = Modifier.padding(start = 10.dp),
              text = stringResource(StringsR.string.settings_online_source_verifying),
              style = MaterialTheme.typography.bodySmall,
            )
          }
        }
        if (errorResId != null) {
          Text(
            modifier = Modifier.padding(top = 12.dp),
            text = stringResource(errorResId),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
          )
        }
      }
    },
    confirmButton = {
      TextButton(
        onClick = { onConfirm(text) },
        enabled = text.isNotBlank() && !verifying,
      ) {
        Text(stringResource(StringsR.string.common_dialog_confirm))
      }
    },
    dismissButton = {
      TextButton(
        onClick = onDismiss,
        enabled = !verifying,
      ) {
        Text(stringResource(StringsR.string.common_dialog_cancel))
      }
    },
  )
}
