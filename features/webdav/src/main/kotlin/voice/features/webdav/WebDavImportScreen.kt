package voice.features.webdav

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.retain.retain
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import voice.core.common.rootGraphAs
import voice.core.ui.icons.VoiceIcons
import voice.core.webdav.WebDavServer
import voice.navigation.Origin
import voice.core.strings.R as StringsR

/** Where the shelf imports its books from: the folders, and the cache. */
@Composable
fun WebDavImportScreen(origin: Origin) {
  val viewModel = retain(origin.name) {
    rootGraphAs<WebDavSettingsGraph>()
      .webDavSettingsViewModelFactory
      .create(origin)
  }
  val viewState = viewModel.viewState()
  WebDavImportView(
    viewState = viewState,
    listener = viewModel,
  )
}

@Composable
private fun WebDavImportView(
  viewState: WebDavSettingsViewState,
  listener: WebDavSettingsViewModel,
) {
  Scaffold(
    topBar = {
      TopAppBar(
        title = {
          Text(stringResource(StringsR.string.webdav_section_import))
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
          title = stringResource(StringsR.string.webdav_settings_book_folder_title),
          subtitle = stringResource(StringsR.string.webdav_settings_book_folder_summary),
          onClick = listener::openBookFolders,
        )
      }
      item {
        SettingRow(
          icon = VoiceIcons.Download,
          title = stringResource(StringsR.string.webdav_cache_title),
          subtitle = stringResource(StringsR.string.webdav_cache_settings_summary),
          onClick = listener::openCacheSettings,
        )
      }
    }
  }
  viewState.serverPicker?.let { servers ->
    ServerPickerDialog(
      servers = servers,
      onSelect = listener::browseServer,
      onDismiss = listener::dismissServerPicker,
    )
  }
}

@Composable
private fun ServerPickerDialog(
  servers: List<WebDavServer>,
  onSelect: (String) -> Unit,
  onDismiss: () -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = {
      Text(stringResource(StringsR.string.webdav_server_pick_title))
    },
    text = {
      Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
        servers.forEach { server ->
          ChoiceRow(
            title = server.name,
            subtitle = server.baseUrl,
            selected = false,
            showDivider = server != servers.last(),
            onClick = { onSelect(server.id) },
          )
        }
      }
    },
    confirmButton = {
      TextButton(onClick = onDismiss) {
        Text(stringResource(StringsR.string.common_action_cancel))
      }
    },
  )
}
