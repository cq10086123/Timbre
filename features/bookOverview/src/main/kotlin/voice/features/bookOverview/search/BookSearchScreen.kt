package voice.features.bookOverview.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import voice.core.data.BookId
import voice.core.ui.icons.VoiceIcons
import voice.features.bookOverview.overview.BookOverviewLayoutMode
import voice.features.bookOverview.views.GridBook
import voice.features.bookOverview.views.ListBookRow
import voice.features.bookOverview.views.gridColumnCount
import voice.core.strings.R as StringsR

@Composable
internal fun BookSearchContent(
  viewState: BookSearchViewState,
  contentPadding: PaddingValues,
  onQueryChange: (String) -> Unit,
  onBookClick: (BookId) -> Unit,
  onClearSearchHistory: () -> Unit,
) {
  when (viewState) {
    is BookSearchViewState.EmptySearch -> {
      LazyColumn(contentPadding = contentPadding) {
        item {
          Spacer(modifier = Modifier.size(16.dp))
        }
        if (viewState.recentQueries.isNotEmpty()) {
          item {
            Row(
              modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
              horizontalArrangement = Arrangement.End,
            ) {
              TextButton(onClick = onClearSearchHistory) {
                Text(stringResource(StringsR.string.library_search_clear_history))
              }
            }
          }
        }
        items(viewState.recentQueries) { query ->
          ListItem(
            modifier = Modifier.clickable { onQueryChange(query) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            leadingContent = {
              Icon(
                imageVector = VoiceIcons.History,
                contentDescription = stringResource(id = StringsR.string.cover_search_recent_content_description),
              )
            },
          ) {
            Text(query)
          }
        }
      }
    }
    is BookSearchViewState.SearchResults -> {
      Column(modifier = Modifier.padding(contentPadding)) {
        when (viewState.layoutMode) {
          BookOverviewLayoutMode.List -> {
            LazyColumn(
              modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
              verticalArrangement = Arrangement.spacedBy(8.dp),
              content = {
                items(viewState.books) { book ->
                  ListBookRow(
                    book = book,
                    onBookClick = onBookClick,
                    onBookLongClick = onBookClick,
                    // search results never carry an import error, so there is
                    // nothing to retry here
                    onRetryImport = {},
                  )
                }
              },
            )
          }
          BookOverviewLayoutMode.Grid -> {
            LazyVerticalGrid(
              columns = GridCells.Fixed(gridColumnCount()),
              verticalArrangement = Arrangement.spacedBy(8.dp),
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 24.dp, bottom = 4.dp),
              content = {
                items(viewState.books) { book ->
                  GridBook(
                    book = book,
                    onBookClick = onBookClick,
                    onBookLongClick = onBookClick,
                    // search results never carry an import error, so there is
                    // nothing to retry here
                    onRetryImport = {},
                  )
                }
              },
            )
          }
        }
        OnlineSearchSection(
          query = viewState.query,
          onBookClick = onBookClick,
        )
      }
    }
  }
}
