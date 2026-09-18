package voice.features.webdav

import android.content.Context
import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.retain.retain
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides
import voice.core.common.rootGraphAs
import voice.core.ui.icons.VoiceIcons
import voice.core.webdav.WebDavCacheSettings
import voice.navigation.Destination
import voice.navigation.NavEntryProvider
import voice.core.strings.R as StringsR

@ContributesTo(AppScope::class)
interface WebDavCacheGraph {
  val webDavCacheViewModel: WebDavCacheViewModel
}

@ContributesTo(AppScope::class)
interface WebDavCacheProvider {

  @Provides
  @IntoSet
  fun webDavCacheNavEntryProvider(): NavEntryProvider<*> = NavEntryProvider<Destination.WebDavCache> { key ->
    NavEntry(key) {
      WebDavCacheScreen()
    }
  }
}

@Composable
fun WebDavCacheScreen() {
  val viewModel = retain<WebDavCacheViewModel> {
    rootGraphAs<WebDavCacheGraph>()
      .webDavCacheViewModel
  }
  val viewState = viewModel.viewState()
  WebDavCacheView(
    viewState = viewState,
    listener = viewModel,
  )
}

@Composable
private fun WebDavCacheView(
  viewState: WebDavCacheViewState,
  listener: WebDavCacheViewModel,
) {
  var showClearConfirmation by remember { mutableStateOf(false) }
  val context = LocalContext.current

  Scaffold(
    topBar = {
      TopAppBar(
        title = {
          Text(stringResource(StringsR.string.webdav_cache_title))
        },
        navigationIcon = {
          IconButton(onClick = listener::back) {
            Icon(imageVector = VoiceIcons.ArrowBack, contentDescription = stringResource(StringsR.string.common_action_close))
          }
        },
      )
    },
  ) { contentPadding ->
    Column(
      modifier = Modifier
        .padding(contentPadding)
        .verticalScroll(rememberScrollState()),
    ) {
      ListItem(
        headlineContent = {
          Text(
            stringResource(
              StringsR.string.webdav_cache_usage,
              formatBytes(context, viewState.cachedBytes),
              formatBytes(context, viewState.settings.maxBytes),
            ),
          )
        },
      )
      ListItem(
        headlineContent = {
          Text(stringResource(StringsR.string.webdav_cache_clear))
        },
        modifier = Modifier.clickable { showClearConfirmation = true },
      )

      Text(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        text = stringResource(StringsR.string.webdav_cache_max),
      )
      Row(modifier = Modifier.padding(horizontal = 16.dp)) {
        Chip("512 MB", selected = viewState.settings.maxBytes == MB_512) { listener.setMaxBytes(MB_512) }
        Spacer(Modifier.size(4.dp))
        Chip("1 GB", selected = viewState.settings.maxBytes == GB_1) { listener.setMaxBytes(GB_1) }
        Spacer(Modifier.size(4.dp))
        Chip("2 GB", selected = viewState.settings.maxBytes == GB_2) { listener.setMaxBytes(GB_2) }
        Spacer(Modifier.size(4.dp))
        Chip("4 GB", selected = viewState.settings.maxBytes == GB_4) { listener.setMaxBytes(GB_4) }
      }
      Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Chip(
          stringResource(StringsR.string.webdav_cache_max_off),
          selected = viewState.settings.maxBytes == 0L,
        ) { listener.setMaxBytes(0L) }
      }

      Text(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        text = stringResource(StringsR.string.webdav_cache_prefetch),
      )
      Column {
        Row(modifier = Modifier.padding(horizontal = 16.dp)) {
          Chip(
            stringResource(StringsR.string.webdav_cache_prefetch_fill),
            selected = viewState.settings.prefetchMode == WebDavCacheSettings.PrefetchMode.FillBook,
          ) { listener.setPrefetchMode(WebDavCacheSettings.PrefetchMode.FillBook) }
          Spacer(Modifier.size(4.dp))
          Chip(
            stringResource(StringsR.string.webdav_cache_prefetch_20),
            selected = viewState.settings.prefetchMode == WebDavCacheSettings.PrefetchMode.Chapters20,
          ) { listener.setPrefetchMode(WebDavCacheSettings.PrefetchMode.Chapters20) }
          Spacer(Modifier.size(4.dp))
          Chip(
            stringResource(StringsR.string.webdav_cache_prefetch_5),
            selected = viewState.settings.prefetchMode == WebDavCacheSettings.PrefetchMode.Chapters5,
          ) { listener.setPrefetchMode(WebDavCacheSettings.PrefetchMode.Chapters5) }
        }
        Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
          Chip(
            stringResource(StringsR.string.webdav_cache_prefetch_off),
            selected = viewState.settings.prefetchMode == WebDavCacheSettings.PrefetchMode.Disabled,
          ) { listener.setPrefetchMode(WebDavCacheSettings.PrefetchMode.Disabled) }
        }
      }

      ListItem(
        headlineContent = {
          Text(stringResource(StringsR.string.webdav_cache_metered))
        },
        trailingContent = {
          Switch(
            checked = viewState.settings.prefetchOnMetered,
            onCheckedChange = listener::setPrefetchOnMetered,
          )
        },
      )
    }
  }

  if (showClearConfirmation) {
    AlertDialog(
      onDismissRequest = { showClearConfirmation = false },
      title = {
        Text(stringResource(StringsR.string.webdav_cache_clear))
      },
      text = {
        Text(stringResource(StringsR.string.webdav_cache_clear_confirm))
      },
      confirmButton = {
        TextButton(
          onClick = {
            showClearConfirmation = false
            listener.clearCache()
          },
        ) {
          Text(stringResource(StringsR.string.webdav_cache_clear))
        }
      },
      dismissButton = {
        TextButton(onClick = { showClearConfirmation = false }) {
          Text(stringResource(StringsR.string.common_dialog_cancel))
        }
      },
    )
  }
}

@Composable
private fun Chip(
  label: String,
  selected: Boolean,
  onClick: () -> Unit,
) {
  FilterChip(
    selected = selected,
    onClick = onClick,
    label = {
      Text(label)
    },
  )
}

private fun formatBytes(
  context: Context,
  bytes: Long,
): String {
  return if (bytes <= 0L) {
    "0 MB"
  } else {
    Formatter.formatShortFileSize(context, bytes)
  }
}

private const val MB_512 = 512L * 1024L * 1024L
private const val GB_1 = 1024L * 1024L * 1024L
private const val GB_2 = 2L * 1024L * 1024L * 1024L
private const val GB_4 = 4L * 1024L * 1024L * 1024L
