package voice.features.bookOverview.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import voice.core.common.rootGraphAs
import voice.core.online.OnlineChapter
import voice.core.online.OnlineSearchResult
import voice.core.online.OnlineSourceClient
import voice.core.online.OnlineSourceService
import voice.core.online.OnlineSourceServiceProvider
import voice.core.strings.R as StringsR

private const val SEARCH_DEBOUNCE_MILLIS = 500L

/**
 * Self contained "online" section of the book search: dynamic source chips
 * (exactly the interfaces the download server exposes) plus the results of
 * the currently selected source. Renders nothing when the online source is
 * not enabled in the settings.
 */
@Composable
internal fun OnlineSearchSection(
  query: String,
  modifier: Modifier = Modifier,
) {
  val service = remember { rootGraphAs<OnlineSourceServiceProvider>().onlineSourceService }
  val configured by produceState(initialValue = false) {
    value = runCatching { service.isConfigured() }.getOrDefault(false)
  }
  if (!configured || query.isBlank()) return

  val sources by produceState(initialValue = emptyList()) {
    val intf = runCatching { service.sources() }.getOrDefault(emptyList())
    value = listOf(
      voice.core.online.OnlineSourceInfo(
        name = OnlineSourceClient.SOURCE_MAIN,
        displayName = "",
      ),
    ) + intf
  }
  var selectedSource by remember { mutableStateOf(OnlineSourceClient.SOURCE_MAIN) }
  var loading by remember { mutableStateOf(false) }
  var failed by remember { mutableStateOf(false) }
  var results by remember { mutableStateOf(emptyList<OnlineSearchResult>()) }
  var chaptersFor by remember { mutableStateOf<OnlineSearchResult?>(null) }

  LaunchedEffect(query, selectedSource) {
    if (query.isBlank()) {
      results = emptyList()
      return@LaunchedEffect
    }
    loading = true
    failed = false
    delay(SEARCH_DEBOUNCE_MILLIS)
    results = runCatching { service.search(selectedSource, query) }
      .onFailure { failed = true }
      .getOrDefault(emptyList())
    loading = false
  }

  Column(modifier = modifier.fillMaxWidth()) {
    // source chips: one per interface the server exposes, plus the main catalog
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .horizontalScroll(rememberScrollState())
        .padding(horizontal = 16.dp, vertical = 4.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = stringResource(StringsR.string.search_online_section_title),
        modifier = Modifier.padding(end = 4.dp),
      )
      if (sources.isEmpty()) {
        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
      }
      sources.forEach { source ->
        FilterChip(
          selected = selectedSource == source.name,
          onClick = { selectedSource = source.name },
          label = {
            Text(
              text = source.displayName.ifBlank {
                if (source.name == OnlineSourceClient.SOURCE_MAIN) {
                  stringResource(StringsR.string.search_online_source_main)
                } else {
                  source.name
                }
              },
            )
          },
        )
      }
    }
    when {
      loading -> {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
          horizontalArrangement = Arrangement.Center,
        ) {
          CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
        }
      }
      failed -> {
        Text(
          modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
          text = stringResource(StringsR.string.search_online_error),
        )
      }
      else -> {
        LazyColumn {
          items(results, key = { it.source + it.bookId + it.title }) { result ->
            OnlineResultRow(
              result = result,
              onClick = { chaptersFor = result },
            )
          }
          if (results.isEmpty()) {
            item {
              Text(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                text = stringResource(StringsR.string.search_online_no_results),
              )
            }
          }
          item {
            Spacer(modifier = Modifier.size(24.dp))
          }
        }
      }
    }
  }

  chaptersFor?.let { book ->
    OnlineChaptersDialog(
      service = service,
      book = book,
      onDismiss = { chaptersFor = null },
    )
  }
}

@Composable
private fun OnlineResultRow(
  result: OnlineSearchResult,
  onClick: () -> Unit,
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clickable(onClick = onClick)
      .padding(horizontal = 16.dp, vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    AsyncImage(
      model = result.cover,
      contentDescription = null,
      modifier = Modifier.size(width = 52.dp, height = 70.dp),
    )
    Column(
      modifier = Modifier
        .weight(1f)
        .padding(start = 12.dp),
    ) {
      Text(text = result.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
      val subtitle = listOf(result.author, result.trackCount.toString())
        .filter { it.isNotBlank() }
        .joinToString(" · ")
      if (subtitle.isNotBlank()) {
        Text(
          text = subtitle,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
  }
}

@Composable
private fun OnlineChaptersDialog(
  service: OnlineSourceService,
  book: OnlineSearchResult,
  onDismiss: () -> Unit,
) {
  val chaptersState by produceState(initialValue = emptyList<OnlineChapter>(), book) {
    value = runCatching { service.chapters(book.source, book.bookId) }.getOrDefault(emptyList())
  }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(book.title, maxLines = 3, overflow = TextOverflow.Ellipsis) },
    text = {
      if (chaptersState.isEmpty()) {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
          horizontalArrangement = Arrangement.Center,
        ) {
          CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
        }
      } else {
        LazyColumn(
          modifier = Modifier.size(width = 280.dp, height = 360.dp),
          verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          items(chaptersState) { chapter ->
            Row(
              modifier = Modifier.fillMaxWidth(),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Text(
                text = chapter.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
              )
              if (chapter.durationSeconds > 0) {
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = formatDuration(chapter.durationSeconds))
              }
            }
          }
        }
      }
    },
    confirmButton = {
      TextButton(onClick = onDismiss) {
        Text(stringResource(StringsR.string.common_dialog_cancel))
      }
    },
  )
}

private fun formatDuration(seconds: Int): String {
  val minutes = seconds / 60
  return if (minutes >= 60) {
    "${minutes / 60}h${minutes % 60}m"
  } else {
    "${minutes}m"
  }
}
