package voice.features.webdav

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides
import voice.core.common.rootGraphAs
import voice.core.ui.icons.VoiceIcons
import voice.navigation.Destination
import voice.navigation.NavEntryProvider
import voice.core.strings.R as StringsR

@ContributesTo(AppScope::class)
interface WebDavBrowseGraph {
  val webDavBrowseViewModelFactory: WebDavBrowseViewModel.Factory
}

@ContributesTo(AppScope::class)
interface WebDavBrowseProvider {

  @Provides
  @IntoSet
  fun webDavBrowseNavEntryProvider(): NavEntryProvider<*> = NavEntryProvider<Destination.WebDavBrowse> { key ->
    NavEntry(key) {
      WebDavBrowseScreen(serverId = key.serverId)
    }
  }
}

@Composable
fun WebDavBrowseScreen(serverId: String) {
  val viewModel = retain(serverId) {
    rootGraphAs<WebDavBrowseGraph>()
      .webDavBrowseViewModelFactory
      .create(serverId)
  }
  val viewState = viewModel.viewState()
  WebDavBrowseView(
    viewState = viewState,
    listener = viewModel,
  )
}

@Composable
private fun WebDavBrowseView(
  viewState: WebDavBrowseViewState,
  listener: WebDavBrowseViewModel,
) {
  Scaffold(
    topBar = {
      TopAppBar(
        title = {
          Text(viewState.path.ifBlank { viewState.serverName })
        },
        navigationIcon = {
          IconButton(onClick = listener::back) {
            Icon(imageVector = VoiceIcons.ArrowBack, contentDescription = stringResource(StringsR.string.common_action_close))
          }
        },
        actions = {
          IconButton(onClick = listener::up) {
            Icon(imageVector = VoiceIcons.ChevronLeft, contentDescription = stringResource(StringsR.string.webdav_browse_up))
          }
        },
      )
    },
    bottomBar = {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(16.dp),
        horizontalArrangement = Arrangement.Center,
      ) {
        FilledTonalButton(onClick = listener::addAsBook) {
          Text(stringResource(StringsR.string.webdav_add_as_book))
        }
        Spacer(Modifier.padding(4.dp))
        FilledTonalButton(onClick = listener::addAsLibraryRoot) {
          Text(stringResource(StringsR.string.webdav_add_as_library_root))
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
      viewState.error -> {
        Text(
          modifier = Modifier.padding(contentPadding).padding(24.dp),
          text = stringResource(StringsR.string.webdav_error),
        )
      }
      else -> {
        LazyColumn(contentPadding = contentPadding) {
          items(viewState.entries, key = { it.url }) { entry ->
            ListItem(
              modifier = Modifier.clickable { listener.open(entry) },
              leadingContent = {
                Icon(
                  imageVector = if (entry.isDirectory) VoiceIcons.Folder else VoiceIcons.AudioFile,
                  contentDescription = null,
                )
              },
              headlineContent = {
                Text(entry.name)
              },
            )
          }
        }
      }
    }
  }
}
