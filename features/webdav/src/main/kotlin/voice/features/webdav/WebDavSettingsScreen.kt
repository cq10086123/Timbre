package voice.features.webdav

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.retain.retain
import androidx.compose.ui.res.stringResource
import androidx.navigation3.runtime.NavEntry
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides
import voice.core.common.rootGraphAs
import voice.core.ui.icons.VoiceIcons
import voice.navigation.Destination
import voice.navigation.NavEntryProvider
import voice.navigation.Origin
import voice.core.strings.R as StringsR

@ContributesTo(AppScope::class)
interface WebDavSettingsGraph {
  val webDavSettingsViewModelFactory: WebDavSettingsViewModel.Factory
}

@ContributesTo(AppScope::class)
interface WebDavSettingsProvider {

  @Provides
  @IntoSet
  fun webDavSettingsNavEntryProvider(): NavEntryProvider<*> = NavEntryProvider<Destination.WebDavSettings> { key ->
    NavEntry(key) {
      WebDavSettingsScreen(origin = key.origin)
    }
  }

  @Provides
  @IntoSet
  fun webDavImportNavEntryProvider(): NavEntryProvider<*> = NavEntryProvider<Destination.WebDavImport> { key ->
    NavEntry(key) {
      WebDavImportScreen(origin = key.origin)
    }
  }
}

/** The WebDAV hub: three pages, each with its own subject. */
@Composable
fun WebDavSettingsScreen(origin: Origin) {
  val viewModel = retain(origin.name) {
    rootGraphAs<WebDavSettingsGraph>()
      .webDavSettingsViewModelFactory
      .create(origin)
  }
  WebDavSettingsView(listener = viewModel)
}

@Composable
private fun WebDavSettingsView(listener: WebDavSettingsViewModel) {
  Scaffold(
    topBar = {
      TopAppBar(
        title = {
          Text(stringResource(StringsR.string.webdav_settings_page_title))
        },
        navigationIcon = {
          IconButton(onClick = listener::back) {
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
        SettingRow(
          icon = VoiceIcons.LibraryBooks,
          title = stringResource(StringsR.string.webdav_section_import),
          subtitle = stringResource(StringsR.string.webdav_settings_import_summary),
          onClick = listener::openImport,
        )
      }
      item {
        SettingRow(
          icon = VoiceIcons.Sync,
          title = stringResource(StringsR.string.webdav_section_backup),
          subtitle = stringResource(StringsR.string.webdav_settings_backup_summary),
          onClick = listener::openBackup,
        )
      }
      item {
        SettingRow(
          icon = VoiceIcons.Language,
          title = stringResource(StringsR.string.webdav_section_servers),
          subtitle = stringResource(StringsR.string.webdav_settings_servers_summary),
          onClick = listener::openServers,
        )
      }
    }
  }
}
