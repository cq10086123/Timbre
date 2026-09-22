package voice.features.bookOverview.search

import android.widget.Toast
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import voice.core.common.rootGraphAs
import voice.core.data.BookId
import voice.core.online.OnlineBook
import voice.core.online.OnlineBookRef
import voice.core.online.OnlineChapter
import voice.core.online.OnlinePlaybackCatalog
import voice.core.online.OnlineSearchResult
import voice.core.online.OnlineSourceClient
import voice.core.online.OnlineSourceService
import voice.core.online.OnlineSourceServiceProvider
import voice.core.online.OnlineUri
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
  onBookClick: (BookId) -> Unit = {},
) {
  val graph = remember { rootGraphAs<OnlineSourceServiceProvider>() }
  val service = graph.onlineSourceService
  val catalog = graph.onlinePlaybackCatalog
  val configured by produceState(initialValue = false) {
    value = runCatching { service.isConfigured() }.getOrDefault(false)
  }
  if (!configured || query.isBlank()) return

  val sources by produceState(initialValue = emptyList()) {
    val intf = runCatching { service.sources() }.getOrDefault(emptyList())
      // "official" interfaces need a VIP account + browser automation on the
      // server for chapter access, so their results can never be played here
      .filterNot { it.type == "official" }
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
    results = try {
      service.search(selectedSource, query)
    } catch (e: CancellationException) {
      // another query or source replaced this one: a cancelled search is not a
      // failed search, and its error must not flash up under the new one
      throw e
    } catch (e: Exception) {
      failed = true
      emptyList()
    }
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
      catalog = catalog,
      book = book,
      onDismiss = { chaptersFor = null },
      onBookClick = onBookClick,
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

private data class ChaptersUiState(
  val loading: Boolean = true,
  val failed: Boolean = false,
  val errorMessage: String? = null,
  val chapters: List<OnlineChapter> = emptyList(),
)

@Composable
private fun OnlineChaptersDialog(
  service: OnlineSourceService,
  catalog: OnlinePlaybackCatalog,
  book: OnlineSearchResult,
  onDismiss: () -> Unit,
  onBookClick: (BookId) -> Unit,
) {
  var reloadKey by remember { mutableIntStateOf(0) }
  val scope = rememberCoroutineScope()
  // shelf membership is explicit: the dialog checks the stored books once and
  // the button toggles add/remove; tapping a chapter only starts playback
  val shelfKey = OnlineBookRef(book.source, book.bookId).key
  var shelfState by remember(book) { mutableStateOf<Boolean?>(null) }
  // measured stream durations land after playback or the probe-ahead: refresh
  // the displayed per-chapter durations when they do
  val durationsVersion by remember { catalog.durationsVersion }.collectAsState()
  LaunchedEffect(book, reloadKey) {
    shelfState = service.shelfBook(shelfKey) != null
  }
  fun playFromChapter(
    chapters: List<OnlineChapter>,
    chapter: OnlineChapter,
  ) {
    // playback without adding to the shelf: hand the fetched book to the
    // catalog so the player can assemble it from memory
    catalog.stashForPlayback(
      OnlineBook(
        source = book.source,
        bookId = book.bookId,
        title = book.title,
        author = book.author,
        cover = book.cover,
        chapters = chapters,
      ),
    )
    catalog.requestStartAt(book.source, book.bookId, chapter.id)
    onBookClick(BookId(OnlineUri.buildBookUri(book.source, book.bookId)))
  }
  // chapters only play from the shelf: tapping one without adding resolves
  // nothing and used to end in a connection error
  val context = LocalContext.current
  val joinShelfFirst = stringResource(StringsR.string.search_online_join_shelf_first)
  fun onChapterClick(
    chapters: List<OnlineChapter>,
    chapter: OnlineChapter,
  ) {
    if (shelfState == true) {
      playFromChapter(chapters, chapter)
    } else {
      Toast.makeText(context, joinShelfFirst, Toast.LENGTH_SHORT).show()
    }
  }
  fun toggleShelf(chapters: List<OnlineChapter>) {
    scope.launch {
      val stored = OnlineBook(
        source = book.source,
        bookId = book.bookId,
        title = book.title,
        author = book.author,
        cover = book.cover,
        chapters = chapters,
      )
      if (shelfState == true) {
        service.removeFromShelf(shelfKey)
        shelfState = false
      } else {
        service.addToShelf(stored)
        shelfState = true
      }
    }
  }
  val state by produceState(initialValue = ChaptersUiState(), book, reloadKey) {
    value = ChaptersUiState(loading = true)
    val result = runCatching { service.chapters(book.source, book.bookId) }
    value = result.fold(
      onSuccess = { ChaptersUiState(loading = false, chapters = it) },
      onFailure = {
        ChaptersUiState(loading = false, failed = true, errorMessage = it.message)
      },
    )
  }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(book.title, maxLines = 3, overflow = TextOverflow.Ellipsis) },
    text = {
      if (state.loading) {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
        ) {
          CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
          Text(
            modifier = Modifier.padding(top = 12.dp),
            text = if (book.trackCount > 0) {
              stringResource(StringsR.string.search_online_chapters_loading_count, book.trackCount)
            } else {
              stringResource(StringsR.string.search_online_chapters_loading)
            },
          )
          Text(
            modifier = Modifier.padding(top = 4.dp),
            text = stringResource(StringsR.string.search_online_chapters_loading_hint),
            style = MaterialTheme.typography.bodySmall,
          )
        }
      } else if (state.failed) {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
        ) {
          Text(stringResource(StringsR.string.search_online_error))
          state.errorMessage?.let { detail ->
            Text(
              modifier = Modifier.padding(top = 6.dp),
              text = detail,
              style = MaterialTheme.typography.bodySmall,
              maxLines = 4,
              overflow = TextOverflow.Ellipsis,
            )
          }
          TextButton(
            modifier = Modifier.padding(top = 8.dp),
            onClick = { reloadKey++ },
          ) {
            Text(stringResource(StringsR.string.search_online_retry))
          }
        }
      } else {
        if (state.chapters.isEmpty()) {
          Text(
            modifier = Modifier
              .fillMaxWidth()
              .padding(vertical = 24.dp),
            text = stringResource(StringsR.string.search_online_chapters_empty),
            style = MaterialTheme.typography.bodySmall,
          )
        } else {
          var page by remember { mutableIntStateOf(0) }
          val pageCount = (state.chapters.size + CHAPTERS_PAGE_SIZE - 1) / CHAPTERS_PAGE_SIZE
          val safePage = page.coerceIn(0, pageCount - 1)
          val pageChapters = state.chapters.drop(safePage * CHAPTERS_PAGE_SIZE).take(CHAPTERS_PAGE_SIZE)
          Column(modifier = Modifier.fillMaxWidth()) {
            Text(
              modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
              text = stringResource(StringsR.string.search_online_chapters_count, state.chapters.size),
              style = MaterialTheme.typography.bodyMedium,
            )
            if (shelfState == true) {
              Text(
                modifier = Modifier.padding(bottom = 8.dp),
                text = stringResource(StringsR.string.search_online_added_to_shelf),
                style = MaterialTheme.typography.bodySmall,
              )
            }
            if (pageCount > 1) {
              Row(
                modifier = Modifier
                  .fillMaxWidth()
                  .horizontalScroll(rememberScrollState())
                  .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
              ) {
                repeat(pageCount) { index ->
                  FilterChip(
                    selected = index == safePage,
                    onClick = { page = index },
                    label = { Text(pageLabel(index, state.chapters.size)) },
                  )
                }
              }
            }
            LazyColumn(
              modifier = Modifier.size(width = 280.dp, height = 360.dp),
              verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
              items(pageChapters) { chapter ->
                val shownSeconds = remember(chapter.id, durationsVersion) {
                  catalog.measuredDurationMs(book.source, book.bookId, chapter.id)
                    ?.div(1_000L)?.toInt()
                    ?: chapter.durationSeconds
                }
                Row(
                  modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                      onChapterClick(state.chapters, chapter)
                    },
                  verticalAlignment = Alignment.CenterVertically,
                ) {
                  Text(
                    text = chapter.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                  )
                  if (shownSeconds > 0) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = formatDuration(shownSeconds))
                  }
                }
              }
            }
          }
        }
      }
    },
    confirmButton = {
      Row {
        TextButton(
          enabled = !state.loading && !state.failed,
          onClick = { toggleShelf(state.chapters) },
        ) {
          Text(
            stringResource(
              if (shelfState == true) {
                StringsR.string.online_shelf_remove
              } else {
                StringsR.string.online_shelf_add
              },
            ),
          )
        }
        TextButton(onClick = onDismiss) {
          Text(stringResource(StringsR.string.common_dialog_cancel))
        }
      }
    },
  )
}

private const val CHAPTERS_PAGE_SIZE = 50

private fun pageLabel(
  page: Int,
  total: Int,
): String {
  val start = page * CHAPTERS_PAGE_SIZE + 1
  val end = minOf((page + 1) * CHAPTERS_PAGE_SIZE, total)
  return "$start-$end"
}

private fun formatDuration(seconds: Int): String {
  val minutes = seconds / 60
  return if (minutes >= 60) {
    "${minutes / 60}h${minutes % 60}m"
  } else {
    "${minutes}m"
  }
}
