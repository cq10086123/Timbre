package voice.features.webdav

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.retain.retain
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
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
interface WebDavBackupFolderGraph {
  val webDavBackupFolderViewModelFactory: WebDavBackupFolderViewModel.Factory
}

@ContributesTo(AppScope::class)
interface WebDavBackupFolderProvider {

  @Provides
  @IntoSet
  fun webDavBackupFolderNavEntryProvider(): NavEntryProvider<*> {
    return NavEntryProvider<Destination.WebDavBackupFolder> { key ->
      NavEntry(key) {
        WebDavBackupFolderScreen(origin = key.origin)
      }
    }
  }
}

@Composable
fun WebDavBackupFolderScreen(origin: Origin) {
  val viewModel = retain(origin.name) {
    rootGraphAs<WebDavBackupFolderGraph>()
      .webDavBackupFolderViewModelFactory
      .create(origin)
  }
  val viewState = viewModel.viewState()
  WebDavBackupFolderView(
    viewState = viewState,
    listener = viewModel,
  )
}

@Composable
private fun WebDavBackupFolderView(
  viewState: WebDavBackupFolderViewState,
  listener: WebDavBackupFolderViewModel,
) {
  Scaffold(
    topBar = {
      TopAppBar(
        title = {
          if (viewState.browsing) {
            Text("${viewState.serverName}${viewState.relativePath}")
          } else {
            Text(stringResource(StringsR.string.webdav_backup_folder_choose_server))
          }
        },
        navigationIcon = {
          IconButton(onClick = listener::back) {
            Icon(
              imageVector = VoiceIcons.ArrowBack,
              contentDescription = stringResource(StringsR.string.common_action_close),
            )
          }
        },
        actions = {
          if (viewState.browsing) {
            IconButton(onClick = listener::up) {
              Icon(
                imageVector = VoiceIcons.ChevronLeft,
                contentDescription = stringResource(StringsR.string.webdav_browse_up),
              )
            }
          }
        },
      )
    },
    bottomBar = {
      if (viewState.browsing && viewState.error == null) {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
          horizontalArrangement = Arrangement.Center,
        ) {
          FilledTonalButton(onClick = listener::useCurrentFolder) {
            Text(stringResource(StringsR.string.webdav_backup_folder_use))
          }
        }
      }
    },
  ) { contentPadding ->
    when {
      viewState.loading -> {
        CircularProgressIndicator(
          modifier = Modifier.padding(contentPadding).padding(32.dp),
        )
      }
      viewState.error != null -> {
        Text(
          modifier = Modifier.padding(contentPadding).padding(24.dp),
          text = if (viewState.error == WebDavBrowseError.Auth) {
            stringResource(StringsR.string.webdav_error_auth)
          } else {
            stringResource(StringsR.string.webdav_error)
          },
        )
      }
      !viewState.browsing && viewState.servers.isEmpty() -> {
        Text(
          modifier = Modifier.padding(contentPadding).padding(24.dp),
          text = stringResource(StringsR.string.settings_webdav_backup_no_server),
        )
      }
      !viewState.browsing -> {
        LazyColumn(contentPadding = contentPadding) {
          items(viewState.servers, key = { it.id }) { server ->
            ListItem(
              modifier = Modifier.clickable { listener.chooseServer(server.id) },
              leadingContent = {
                Icon(imageVector = VoiceIcons.Language, contentDescription = null)
              },
            ) {
              Text(server.name)
            }
          }
        }
      }
      viewState.entries.isEmpty() -> {
        Text(
          modifier = Modifier.padding(contentPadding).padding(24.dp),
          text = stringResource(StringsR.string.webdav_backup_folder_empty),
        )
      }
      else -> {
        LazyColumn(contentPadding = contentPadding) {
          items(viewState.entries, key = { it.url }) { entry ->
            ListItem(
              modifier = Modifier.clickable { listener.open(entry) },
              leadingContent = {
                Icon(imageVector = VoiceIcons.Folder, contentDescription = null)
              },
            ) {
              Text(entry.name)
            }
          }
        }
      }
    }
  }
}
