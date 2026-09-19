package voice.features.playbackScreen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue.Expanded
import androidx.compose.material3.SheetValue.Hidden
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import voice.core.strings.R as StringsR
import voice.core.ui.icons.VoiceIcons

private const val ITEMS_PER_RANGE_ROW = 3
private val RANGE_GRID_MAX_HEIGHT = 300.dp
private val HIGHLIGHT_DURATION = 2.seconds

@Composable
internal fun SelectChapterDialog(
  dialogState: BookPlayDialogViewState.SelectChapterDialog,
  viewModel: BookPlayViewModel,
) {
  var rangesExpanded by remember { mutableStateOf(false) }
  var highlightedItemIndex by remember { mutableStateOf<Int?>(null) }
  val selectedIndex = dialogState.items.indexOfFirst { it.active }
  // -1 because we want to show the previous chapter on the screen
  val listState = rememberLazyListState(initialFirstVisibleItemIndex = (selectedIndex - 1).coerceAtLeast(0))
  val scope = rememberCoroutineScope()

  LaunchedEffect(highlightedItemIndex) {
    if (highlightedItemIndex != null) {
      delay(HIGHLIGHT_DURATION)
      highlightedItemIndex = null
    }
  }

  ModalBottomSheet(
    sheetState = rememberBottomSheetState(
      initialValue = Hidden,
      enabledValues = setOf(Hidden, Expanded),
    ),
    onDismissRequest = { viewModel.dismissDialog() },
    content = {
      if (dialogState.ranges.isNotEmpty()) {
        RangesHeader(
          itemCount = dialogState.items.size,
          expanded = rangesExpanded,
          onToggle = { rangesExpanded = !rangesExpanded },
        )
        AnimatedVisibility(visible = rangesExpanded) {
          RangeGrid(
            ranges = dialogState.ranges,
            onRangeClick = { range ->
              rangesExpanded = false
              highlightedItemIndex = range.startIndex
              scope.launch { listState.animateScrollToItem(range.startIndex) }
            },
          )
        }
      }
      LazyColumn(
        state = listState,
        content = {
          itemsIndexed(dialogState.items) { index, chapter ->
            val isCurrentChapter = chapter.active
            val description = stringResource(StringsR.string.playback_chapter_current_content_description)
            val backgroundColor = when {
              chapter.active -> MaterialTheme.colorScheme.primaryContainer
              index == highlightedItemIndex -> MaterialTheme.colorScheme.secondaryContainer
              else -> Color.Transparent
            }
            ListItem(
              colors = ListItemDefaults.colors(containerColor = backgroundColor),
              modifier = Modifier
                .padding(3.dp)
                .clip(shape = RoundedCornerShape(12.dp))
                .semantics {
                  selected = chapter.active
                  if (isCurrentChapter) contentDescription = description
                }
                .clickable {
                  viewModel.onChapterClick(number = chapter.number)
                },
              leadingContent = {
                Text(text = chapter.number.toString())
              },
              trailingContent = {
                Text(text = chapter.time)
              },
            ) {
              Text(text = chapter.name)
            }
          }
        },
      )
    },
  )
}

@Composable
private fun RangesHeader(
  itemCount: Int,
  expanded: Boolean,
  onToggle: () -> Unit,
) {
  val chevronRotation by animateFloatAsState(
    targetValue = if (expanded) 180F else 0F,
    label = "rangesChevronRotation",
  )
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clickable(onClick = onToggle)
      .padding(vertical = 12.dp),
    horizontalArrangement = Arrangement.Center,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = stringResource(StringsR.string.playback_chapter_select_total, itemCount),
      style = MaterialTheme.typography.titleMedium,
    )
    Icon(
      imageVector = VoiceIcons.ExpandMore,
      contentDescription = null,
      modifier = Modifier
        .padding(start = 4.dp)
        .graphicsLayer { rotationZ = chevronRotation },
    )
  }
}

@Composable
private fun RangeGrid(
  ranges: List<BookPlayDialogViewState.SelectChapterDialog.RangeViewState>,
  onRangeClick: (BookPlayDialogViewState.SelectChapterDialog.RangeViewState) -> Unit,
) {
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .heightIn(max = RANGE_GRID_MAX_HEIGHT)
      .verticalScroll(rememberScrollState())
      .padding(horizontal = 12.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    ranges.chunked(ITEMS_PER_RANGE_ROW).forEach { rowRanges ->
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        rowRanges.forEach { range ->
          RangeChip(
            range = range,
            onClick = { onRangeClick(range) },
            modifier = Modifier.weight(1F),
          )
        }
        repeat(ITEMS_PER_RANGE_ROW - rowRanges.size) {
          Spacer(modifier = Modifier.weight(1F))
        }
      }
    }
  }
}

@Composable
private fun RangeChip(
  range: BookPlayDialogViewState.SelectChapterDialog.RangeViewState,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Box(
    modifier = modifier
      .clip(shape = RoundedCornerShape(20.dp))
      .background(
        color = if (range.containsCurrent) {
          MaterialTheme.colorScheme.primaryContainer
        } else {
          MaterialTheme.colorScheme.surfaceVariant
        },
      )
      .clickable(onClick = onClick)
      .padding(horizontal = 8.dp, vertical = 10.dp),
    contentAlignment = Alignment.Center,
  ) {
    Text(
      text = "${range.firstNumber}-${range.lastNumber}",
      style = MaterialTheme.typography.bodyMedium,
      color = if (range.containsCurrent) {
        MaterialTheme.colorScheme.onPrimaryContainer
      } else {
        MaterialTheme.colorScheme.onSurfaceVariant
      },
    )
  }
}
