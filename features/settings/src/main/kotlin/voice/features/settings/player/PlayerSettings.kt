package voice.features.settings.player

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.retain.retain
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.navigation3.runtime.NavEntry
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides
import voice.core.common.rootGraphAs
import voice.core.online.OnlinePreloadSettings
import voice.core.ui.icons.VoiceIcons
import voice.features.settings.views.OnlinePreloadEnabledRow
import voice.features.settings.views.OnlinePreloadSliderDialog
import voice.features.settings.views.OnlinePreloadValueRow
import voice.navigation.Destination
import voice.navigation.NavEntryProvider
import voice.core.strings.R as StringsR

@Composable
private fun PlayerSettings(
  viewState: PlayerSettingsViewState,
  viewModel: PlayerSettingsViewModel,
) {
  val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
  Scaffold(
    modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
    topBar = {
      TopAppBar(
        scrollBehavior = scrollBehavior,
        title = {
          Text(stringResource(StringsR.string.settings_player_title))
        },
        navigationIcon = {
          IconButton(onClick = viewModel::close) {
            Icon(
              imageVector = VoiceIcons.ArrowBack,
              contentDescription = stringResource(StringsR.string.common_action_close),
            )
          }
        },
      )
    },
  ) { contentPadding ->
    LazyColumn(contentPadding = contentPadding) {
      item {
        OnlinePreloadEnabledRow(
          checked = viewState.onlinePreloadEnabled,
          onCheck = viewModel::setOnlinePreloadEnabled,
        )
      }

      item {
        OnlinePreloadValueRow(
          titleRes = StringsR.string.settings_playback_online_preload_trigger_title,
          valueText = stringResource(
            StringsR.string.settings_playback_online_preload_trigger_value,
            viewState.onlinePreloadTriggerSeconds,
          ),
        ) {
          viewModel.onOnlinePreloadTriggerRowClick()
        }
      }

      item {
        OnlinePreloadValueRow(
          titleRes = StringsR.string.settings_playback_online_preload_count_title,
          valueText = stringResource(
            StringsR.string.settings_playback_online_preload_count_value,
            viewState.onlinePreloadChapterCount,
          ),
        ) {
          viewModel.onOnlinePreloadCountRowClick()
        }
      }

      item {
        OnlinePreloadValueRow(
          titleRes = StringsR.string.settings_playback_online_preload_interval_title,
          valueText = stringResource(
            StringsR.string.settings_playback_online_preload_interval_value,
            viewState.onlinePreloadIntervalSeconds,
          ),
        ) {
          viewModel.onOnlinePreloadIntervalRowClick()
        }
      }
    }
  }

  when (viewState.dialog) {
    PlayerSettingsViewState.Dialog.OnlinePreloadTrigger -> {
      OnlinePreloadSliderDialog(
        titleRes = StringsR.string.settings_playback_online_preload_trigger_title,
        explanationRes = StringsR.string.settings_playback_online_preload_dialog_trigger,
        valueFormatRes = StringsR.string.settings_playback_online_preload_trigger_value,
        currentValue = viewState.onlinePreloadTriggerSeconds,
        min = OnlinePreloadSettings.MIN_TRIGGER_SECONDS,
        max = OnlinePreloadSettings.MAX_TRIGGER_SECONDS,
        onConfirm = viewModel::onlinePreloadTriggerChanged,
        onDismiss = viewModel::dismissDialog,
      )
    }
    PlayerSettingsViewState.Dialog.OnlinePreloadCount -> {
      OnlinePreloadSliderDialog(
        titleRes = StringsR.string.settings_playback_online_preload_count_title,
        explanationRes = StringsR.string.settings_playback_online_preload_dialog_count,
        valueFormatRes = StringsR.string.settings_playback_online_preload_count_value,
        currentValue = viewState.onlinePreloadChapterCount,
        min = OnlinePreloadSettings.MIN_CHAPTER_COUNT,
        max = OnlinePreloadSettings.MAX_CHAPTER_COUNT,
        onConfirm = viewModel::onlinePreloadCountChanged,
        onDismiss = viewModel::dismissDialog,
      )
    }
    PlayerSettingsViewState.Dialog.OnlinePreloadInterval -> {
      OnlinePreloadSliderDialog(
        titleRes = StringsR.string.settings_playback_online_preload_interval_title,
        explanationRes = StringsR.string.settings_playback_online_preload_dialog_interval,
        valueFormatRes = StringsR.string.settings_playback_online_preload_interval_value,
        currentValue = viewState.onlinePreloadIntervalSeconds,
        min = OnlinePreloadSettings.MIN_INTERVAL_SECONDS,
        max = OnlinePreloadSettings.MAX_INTERVAL_SECONDS,
        onConfirm = viewModel::onlinePreloadIntervalChanged,
        onDismiss = viewModel::dismissDialog,
      )
    }
    null -> {}
  }
}

@ContributesTo(AppScope::class)
interface PlayerSettingsGraph {
  val playerSettingsViewModel: PlayerSettingsViewModel
}

@ContributesTo(AppScope::class)
interface PlayerSettingsProvider {

  @Provides
  @IntoSet
  fun playerSettingsNavEntryProvider(): NavEntryProvider<*> = NavEntryProvider<Destination.PlayerSettings> { key ->
    NavEntry(key) {
      PlayerSettings()
    }
  }
}

@Composable
fun PlayerSettings() {
  val viewModel = retain<PlayerSettingsViewModel> { rootGraphAs<PlayerSettingsGraph>().playerSettingsViewModel }
  val viewState = viewModel.viewState()
  PlayerSettings(viewState, viewModel)
}
