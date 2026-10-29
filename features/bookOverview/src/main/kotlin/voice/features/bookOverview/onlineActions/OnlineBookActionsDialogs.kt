package voice.features.bookOverview.onlineActions

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue.Expanded
import androidx.compose.material3.SheetValue.Hidden
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import voice.core.online.OnlineBookCacheManager
import voice.core.online.OnlineCacheFailure
import voice.core.online.OnlineCacheFailureReason
import voice.core.online.OnlineCacheMark
import voice.core.online.OnlineCacheState
import voice.core.online.OnlineCacheStopReason
import voice.core.online.OnlineCachedInfo
import voice.core.online.OnlineChapterRefreshResult
import voice.features.bookOverview.formatBytes
import voice.core.strings.R as StringsR

@Composable
internal fun OnlineRefreshDialog(
  viewState: OnlineRefreshUiState,
  onDismiss: () -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(StringsR.string.online_actions_refresh_chapters)) },
    text = {
      when (val state = viewState) {
        is OnlineRefreshUiState.Refreshing -> {
          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            Text(stringResource(StringsR.string.online_refresh_loading))
          }
        }
        is OnlineRefreshUiState.Result -> {
          Text(text = state.result.toMessage())
        }
      }
    },
    confirmButton = {
      TextButton(onClick = onDismiss) {
        Text(stringResource(StringsR.string.common_dialog_confirm))
      }
    },
  )
}

@Composable
private fun OnlineChapterRefreshResult.toMessage(): String {
  return when (this) {
    is OnlineChapterRefreshResult.Updated ->
      stringResource(StringsR.string.online_refresh_updated, added, total)
    is OnlineChapterRefreshResult.UpToDate ->
      stringResource(StringsR.string.online_refresh_uptodate, total)
    is OnlineChapterRefreshResult.Failed -> {
      val failureMessage: String? = message
      if (failureMessage.isNullOrBlank()) {
        stringResource(StringsR.string.online_refresh_failed_unknown)
      } else {
        stringResource(StringsR.string.online_refresh_failed, failureMessage)
      }
    }
  }
}

/**
 * The cache sheet is a status report first and a form second: a book title, the
 * live state of a run, and at most one card of options. Everything that used to
 * be a row of chips plus a full width text field is now one settings card with
 * two rows - one dropdown per decision - so the panel reads as a panel instead
 * of a form that happens to scroll.
 *
 * Three states only: cached, pending and failed. The chapter in flight is a
 * pending cell that breathes and the status line names it, so a fourth legend
 * entry for a single cell would only add noise.
 */
@Composable
internal fun OnlineCacheDialog(
  viewState: OnlineCacheDialogState,
  progress: OnlineCacheState?,
  onSelectCount: (Int) -> Unit,
  onCustomCountChange: (String) -> Unit,
  onSelectDelay: (Int) -> Unit,
  onCustomDelayChange: (String) -> Unit,
  onStartCache: () -> Unit,
  onCancelCache: () -> Unit,
  onClearCache: () -> Unit,
  onDismiss: () -> Unit,
) {
  val info = viewState.info
  val remaining = if (info == null || info.totalChapters <= 0) {
    0
  } else {
    (info.totalChapters - info.currentIndex).coerceAtLeast(0)
  }
  val sheetState = rememberBottomSheetState(
    initialValue = Hidden,
    enabledValues = setOf(Hidden, Expanded),
  )
  val scope = rememberCoroutineScope()
  var openedFailure by remember { mutableStateOf<OnlineCacheFailure?>(null) }
  var editing by remember { mutableStateOf<CacheEditTarget?>(null) }
  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
  ) {
    OnlineCacheSheetContent(
      viewState = viewState,
      progress = progress,
      remaining = remaining,
      onSelectCount = onSelectCount,
      onSelectDelay = onSelectDelay,
      onEditCount = { editing = CacheEditTarget.COUNT },
      onEditDelay = { editing = CacheEditTarget.DELAY },
      onStartCache = onStartCache,
      onCancelCache = onCancelCache,
      onClearCache = onClearCache,
      onFailureClick = { openedFailure = it },
      onClose = {
        // close via the sheet state, so the panel slides away instead of
        // vanishing the moment the state drops its dialog
        scope.launch {
          sheetState.hide()
          onDismiss()
        }
      },
    )
  }
  openedFailure?.let { failure ->
    CacheFailureDialog(
      failure = failure,
      onDismiss = { openedFailure = null },
    )
  }
  editing?.let { target ->
    CacheValueDialog(
      title = when (target) {
        CacheEditTarget.COUNT -> stringResource(StringsR.string.online_cache_custom_dialog_title)
        CacheEditTarget.DELAY -> stringResource(StringsR.string.online_cache_delay_seconds_custom)
      },
      initial = when (target) {
        CacheEditTarget.COUNT -> viewState.customCount.ifBlank { viewState.selectedCount.toString() }
        CacheEditTarget.DELAY -> viewState.customDelay.ifBlank { viewState.delaySeconds.toString() }
      },
      // beyond this the manager clamps: the field shows the same cap instead of
      // letting a 27 hour interval sit there and never happen
      max = when (target) {
        CacheEditTarget.COUNT -> remaining.takeIf { it > 0 }
        CacheEditTarget.DELAY -> OnlineBookCacheManager.MAX_EPISODE_DELAY_SECONDS
      },
      hint = if (target == CacheEditTarget.DELAY) {
        pluralStringResource(
          StringsR.plurals.online_cache_delay_custom_hint,
          OnlineBookCacheManager.MAX_EPISODE_DELAY_SECONDS,
          OnlineBookCacheManager.MAX_EPISODE_DELAY_SECONDS,
        )
      } else {
        null
      },
      onConfirm = { value ->
        when (target) {
          CacheEditTarget.COUNT -> onCustomCountChange(value)
          CacheEditTarget.DELAY -> onCustomDelayChange(value)
        }
        editing = null
      },
      onDismiss = { editing = null },
    )
  }
}

/**
 * The sheet body, kept separate from the sheet host so it can be rendered on
 * its own (tests draw it without a bottom sheet window).
 */
@Composable
internal fun OnlineCacheSheetContent(
  viewState: OnlineCacheDialogState,
  progress: OnlineCacheState?,
  remaining: Int,
  onSelectCount: (Int) -> Unit,
  onSelectDelay: (Int) -> Unit,
  onEditCount: () -> Unit,
  onEditDelay: () -> Unit,
  onStartCache: () -> Unit,
  onCancelCache: () -> Unit,
  onClearCache: () -> Unit,
  onFailureClick: (OnlineCacheFailure) -> Unit,
  onClose: () -> Unit,
) {
  val info = viewState.info
  val downloading = progress?.downloading == true
  Column(modifier = Modifier.fillMaxWidth()) {
    CacheHeader(info)
    Column(
      modifier = Modifier
        .weight(weight = 1f, fill = false)
        .verticalScroll(rememberScrollState())
        .padding(horizontal = CACHE_SIDE_PADDING)
        .padding(top = 18.dp, bottom = 8.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      when {
        viewState.loading -> {
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .padding(vertical = 24.dp),
            horizontalArrangement = Arrangement.Center,
          ) {
            CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
          }
        }
        info == null -> {
          Text(stringResource(StringsR.string.search_online_error))
        }
        else -> {
          if (progress != null) {
            CacheStatusCard(
              progress = progress,
              onFailureClick = onFailureClick,
            )
          }
          // while a job runs the options would only collect dust: the sheet
          // stays short and the live state keeps the stage
          if (!downloading) {
            CacheSettings(
              viewState = viewState,
              remaining = remaining,
              onSelectCount = onSelectCount,
              onSelectDelay = onSelectDelay,
              onEditCount = onEditCount,
              onEditDelay = onEditDelay,
            )
          }
        }
      }
    }
    CacheFooter(
      viewState = viewState,
      progress = progress,
      remaining = remaining,
      onStartCache = onStartCache,
      onCancelCache = onCancelCache,
      onClearCache = onClearCache,
      onClose = onClose,
    )
  }
}

private enum class CacheEditTarget { COUNT, DELAY }

/** Book title and what is already on the device, pinned above everything. */
@Composable
private fun CacheHeader(info: OnlineCachedInfo?) {
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = CACHE_SIDE_PADDING),
  ) {
    Text(
      text = info?.title?.takeIf { it.isNotBlank() }
        ?: stringResource(StringsR.string.online_cache_title),
      style = MaterialTheme.typography.titleMedium,
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
    )
    if (info != null && info.totalChapters > 0) {
      Text(
        modifier = Modifier.padding(top = 6.dp),
        text = stringResource(
          StringsR.string.online_cache_cached,
          info.cachedCount,
          info.totalChapters,
          formatBytes(info.cachedBytes),
        ),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

/** Everything about the run: outcome, overall bar, current episode, map, failures. */
@Composable
private fun CacheStatusCard(
  progress: OnlineCacheState,
  onFailureClick: (OnlineCacheFailure) -> Unit,
) {
  val scheme = MaterialTheme.colorScheme
  val total = progress.total
  val fraction = if (total > 0) (progress.done.toFloat() / total).coerceIn(0f, 1f) else 0f
  CacheCard {
    Column(
      modifier = Modifier.padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
          modifier = Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(progress.statusColor(scheme)),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
          text = progress.statusText(),
          style = MaterialTheme.typography.titleSmall,
          color = progress.statusColor(scheme),
        )
      }
      // a run that never got a chapter list has nothing to measure: a 0% bar
      // would claim progress it never made
      if (total > 0 && (progress.marks.isNotEmpty() || progress.downloading)) {
        LinearProgressIndicator(
          progress = { fraction },
          modifier = Modifier
            .fillMaxWidth()
            .height(6.dp),
          strokeCap = StrokeCap.Round,
          trackColor = scheme.outlineVariant.copy(alpha = 0.35f),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(
            modifier = Modifier.weight(1f),
            text = stringResource(StringsR.string.online_cache_progress_count, progress.done, total),
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
          )
          Text(
            text = stringResource(StringsR.string.online_cache_percent, (fraction * 100).toInt()),
            style = MaterialTheme.typography.titleLarge,
            color = scheme.onSurface,
          )
        }
      }
      if (progress.downloading) {
        CurrentChapter(progress)
      }
      if (progress.marks.isNotEmpty()) {
        ChapterMap(
          progress = progress,
          onFailureClick = onFailureClick,
        )
      }
      if (progress.failures.isNotEmpty()) {
        HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.4f))
        FailureList(
          progress = progress,
          onFailureClick = onFailureClick,
        )
      }
    }
  }
}

/** The episode in flight: title, per episode bar and speed. */
@Composable
private fun CurrentChapter(progress: OnlineCacheState) {
  val scheme = MaterialTheme.colorScheme
  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Text(
      text = stringResource(StringsR.string.online_cache_current_chapter, progress.currentTitle),
      style = MaterialTheme.typography.bodyMedium,
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
    )
    val chapterTotal = progress.currentTotalBytes
    if (chapterTotal > 0) {
      LinearProgressIndicator(
        progress = { (progress.currentBytes.toFloat() / chapterTotal).coerceIn(0f, 1f) },
        modifier = Modifier
          .fillMaxWidth()
          .height(4.dp),
        strokeCap = StrokeCap.Round,
        trackColor = scheme.outlineVariant.copy(alpha = 0.35f),
      )
    } else {
      LinearProgressIndicator(
        modifier = Modifier
          .fillMaxWidth()
          .height(4.dp),
        strokeCap = StrokeCap.Round,
        trackColor = scheme.outlineVariant.copy(alpha = 0.35f),
      )
    }
    Text(
      text = progress.chapterLine(),
      style = MaterialTheme.typography.bodySmall,
      color = scheme.onSurfaceVariant,
    )
  }
}

/** One cell per episode: cached, pending (the one in flight breathes) or failed. */
@Composable
private fun ChapterMap(
  progress: OnlineCacheState,
  onFailureClick: (OnlineCacheFailure) -> Unit,
) {
  val scheme = MaterialTheme.colorScheme
  val pulse = rememberCachePulse(enabled = progress.downloading)
  val failedByIndex = remember(progress.failures) { progress.failures.associateBy { it.index } }
  Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
    FlowRow(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(3.dp),
      verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
      progress.marks.forEachIndexed { index, mark ->
        val failure = failedByIndex[index]
        ChapterCell(
          mark = mark,
          pulse = pulse,
          onClick = failure?.let { failed -> { onFailureClick(failed) } },
        )
      }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
      LEGEND_ORDER.forEach { mark -> LegendItem(mark, scheme) }
    }
    if (progress.total > progress.marks.size) {
      Text(
        text = stringResource(
          StringsR.string.online_cache_marks_capped,
          progress.marks.size,
          progress.total,
        ),
        style = MaterialTheme.typography.labelSmall,
        color = scheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
private fun ChapterCell(
  mark: OnlineCacheMark,
  pulse: () -> Float,
  onClick: (() -> Unit)?,
) {
  val scheme = MaterialTheme.colorScheme
  val color = when (mark) {
    OnlineCacheMark.CACHED -> scheme.primary
    OnlineCacheMark.FAILED -> scheme.error
    // the chapter in flight is a pending cell that breathes: one chapter at a
    // time is enough of a "running" state not to need its own legend entry
    OnlineCacheMark.RUNNING -> scheme.primary.copy(alpha = pulse())
    OnlineCacheMark.PENDING -> scheme.outlineVariant
  }
  val cell = Modifier
    .size(12.dp)
    .clip(RoundedCornerShape(3.dp))
    .background(color)
  Box(modifier = if (onClick == null) cell else cell.clickable(onClick = onClick))
}

@Composable
private fun rememberCachePulse(enabled: Boolean): () -> Float {
  if (!enabled) return { 1f }
  val transition = rememberInfiniteTransition(label = "cache-running")
  val alpha by transition.animateFloat(
    initialValue = 0.3f,
    targetValue = 1f,
    animationSpec = infiniteRepeatable(
      animation = tween(durationMillis = 750, easing = FastOutSlowInEasing),
      repeatMode = RepeatMode.Reverse,
    ),
    label = "cache-running-alpha",
  )
  return { alpha }
}

@Composable
private fun LegendItem(
  mark: OnlineCacheMark,
  scheme: ColorScheme,
) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(5.dp),
  ) {
    Box(
      modifier = Modifier
        .size(8.dp)
        .clip(RoundedCornerShape(2.dp))
        .background(mark.legendColor(scheme)),
    )
    Text(
      text = stringResource(mark.labelRes()),
      style = MaterialTheme.typography.labelSmall,
      color = scheme.onSurfaceVariant,
    )
  }
}

/**
 * The failures with their reason: "3 episodes failed" is not actionable, "the
 * source said the link expired" is. A row opens the raw answer.
 */
@Composable
private fun FailureList(
  progress: OnlineCacheState,
  onFailureClick: (OnlineCacheFailure) -> Unit,
) {
  val scheme = MaterialTheme.colorScheme
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(
      text = stringResource(StringsR.string.online_cache_failures_title, progress.failed),
      style = MaterialTheme.typography.labelLarge,
      color = scheme.error,
    )
    progress.failures.forEach { failure ->
      FailureRow(
        failure = failure,
        onClick = { onFailureClick(failure) },
      )
    }
    val hidden = progress.failed - progress.failures.size
    if (hidden > 0) {
      Text(
        text = stringResource(StringsR.string.online_cache_failures_more, hidden),
        style = MaterialTheme.typography.labelSmall,
        color = scheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
private fun FailureRow(
  failure: OnlineCacheFailure,
  onClick: () -> Unit,
) {
  val scheme = MaterialTheme.colorScheme
  Surface(
    onClick = onClick,
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(14.dp),
    color = scheme.surfaceContainerHighest,
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(
        modifier = Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(2.dp),
      ) {
        Text(
          text = failure.cacheTitle(),
          style = MaterialTheme.typography.bodyMedium,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          text = stringResource(failure.reason.labelRes()),
          style = MaterialTheme.typography.bodySmall,
          color = scheme.error,
        )
      }
      Text(
        modifier = Modifier.padding(start = 8.dp),
        text = stringResource(StringsR.string.online_cache_failure_detail_action),
        style = MaterialTheme.typography.labelMedium,
        color = scheme.primary,
      )
    }
  }
}

/**
 * What the source (or the server) actually answered, verbatim and selectable:
 * a cache that only says "resolve failed" cannot be diagnosed, which is
 * exactly the complaint this dialog answers.
 */
@Composable
private fun CacheFailureDialog(
  failure: OnlineCacheFailure,
  onDismiss: () -> Unit,
) {
  val scheme = MaterialTheme.colorScheme
  val raw = failure.detail?.let(::rawCacheErrorText)?.takeIf(String::isNotEmpty)
  AlertDialog(
    onDismissRequest = onDismiss,
    confirmButton = {
      TextButton(onClick = onDismiss) {
        Text(stringResource(StringsR.string.common_action_close))
      }
    },
    title = {
      Text(stringResource(StringsR.string.online_cache_failure_dialog_title))
    },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
          text = failure.cacheTitle(),
          style = MaterialTheme.typography.bodyMedium,
        )
        Text(
          text = stringResource(failure.reason.labelRes()),
          style = MaterialTheme.typography.bodySmall,
          color = scheme.error,
        )
        if (raw == null) {
          Text(
            text = stringResource(StringsR.string.online_cache_failure_dialog_empty),
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
          )
        } else {
          Text(
            text = stringResource(StringsR.string.online_cache_failure_dialog_raw),
            style = MaterialTheme.typography.labelMedium,
            color = scheme.onSurfaceVariant,
          )
          // selectable and scrollable: a source that throws a whole html page
          // must not blow the dialog up, and the text must stay copyable
          Surface(
            shape = RoundedCornerShape(12.dp),
            color = scheme.surfaceContainerHighest,
          ) {
            SelectionContainer {
              Text(
                modifier = Modifier
                  .heightIn(max = MAX_FAILURE_DETAIL_HEIGHT)
                  .verticalScroll(rememberScrollState())
                  .padding(12.dp),
                text = raw,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
              )
            }
          }
        }
      }
    },
  )
}

/**
 * One card, two rows: how many episodes and how far apart. A row opens a menu
 * with the presets, so the sheet never shows ten chips at once - the picks a
 * user actually makes are two, and both live behind the same affordance.
 */
@Composable
private fun CacheSettings(
  viewState: OnlineCacheDialogState,
  remaining: Int,
  onSelectCount: (Int) -> Unit,
  onSelectDelay: (Int) -> Unit,
  onEditCount: () -> Unit,
  onEditDelay: () -> Unit,
) {
  val info = viewState.info
  // the manager clamps a run to what is left, so the menu must not offer 500
  // episodes to a book with 20 left: that pick would cache 20 and still read
  // "500". The remaining entry is the only honest "all of it".
  val countOptions = remember(remaining) {
    if (remaining <= 0) {
      CACHE_COUNT_PRESETS
    } else {
      (CACHE_COUNT_PRESETS.filter { it < remaining } + remaining).sorted()
    }
  }
  val pickedCount = if (remaining > 0) {
    viewState.selectedCount.coerceAtMost(remaining)
  } else {
    viewState.selectedCount
  }
  val allLabel = stringResource(StringsR.string.online_cache_all)
  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    CacheSectionLabel(stringResource(StringsR.string.online_cache_settings))
    CacheCard {
      Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) {
        CacheSettingRow(
          label = stringResource(StringsR.string.online_cache_count_label),
          value = viewState.countSummary(remaining),
          options = countOptions,
          selected = pickedCount.takeIf { viewState.customCount.isBlank() },
          labelOf = { option ->
            if (remaining > 0 && option == remaining) allLabel else option.toString()
          },
          onSelect = onSelectCount,
          onCustom = onEditCount,
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
        CacheSettingRow(
          label = stringResource(StringsR.string.online_cache_delay_label),
          value = viewState.delaySummary(),
          hint = stringResource(StringsR.string.online_cache_delay_hint),
          options = CACHE_DELAY_PRESETS,
          selected = viewState.delaySeconds
            .takeIf { viewState.customDelay.isBlank() && it in CACHE_DELAY_PRESETS },
          labelOf = { option ->
            if (option == 0) {
              stringResource(StringsR.string.online_cache_delay_none)
            } else {
              stringResource(StringsR.string.online_cache_delay_seconds, option)
            }
          },
          onSelect = onSelectDelay,
          onCustom = onEditDelay,
        )
      }
    }
    val startChapter = info?.currentIndex?.plus(1) ?: 0
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
      if (startChapter > 0) {
        Text(
          text = stringResource(StringsR.string.online_cache_from_current, startChapter),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      Text(
        text = stringResource(StringsR.string.online_cache_offline_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
private fun CacheSectionLabel(text: String) {
  Text(
    modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
    text = text,
    style = MaterialTheme.typography.labelLarge,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
  )
}

/** A label, its current value and a menu of the presets plus "custom". */
@Composable
private fun CacheSettingRow(
  label: String,
  value: String,
  options: List<Int>,
  selected: Int?,
  labelOf: @Composable (Int) -> String,
  onSelect: (Int) -> Unit,
  onCustom: () -> Unit,
  hint: String? = null,
) {
  val scheme = MaterialTheme.colorScheme
  var expanded by remember { mutableStateOf(false) }
  Box {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .clickable { expanded = true }
        .padding(vertical = 16.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = label,
          style = MaterialTheme.typography.bodyLarge,
        )
        if (hint != null) {
          Text(
            modifier = Modifier.padding(top = 2.dp, end = 12.dp),
            text = hint,
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurfaceVariant,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }
      Text(
        text = value,
        style = MaterialTheme.typography.titleSmall,
        color = scheme.primary,
      )
      Spacer(modifier = Modifier.width(6.dp))
      Text(
        text = "›",
        style = MaterialTheme.typography.titleMedium,
        color = scheme.onSurfaceVariant,
      )
    }
    DropdownMenu(
      expanded = expanded,
      onDismissRequest = { expanded = false },
    ) {
      options.forEach { option ->
        DropdownMenuItem(
          text = { Text(labelOf(option)) },
          onClick = {
            expanded = false
            onSelect(option)
          },
          trailingIcon = if (option == selected) {
            { Text(text = "✓", color = scheme.primary) }
          } else {
            null
          },
        )
      }
      HorizontalDivider()
      DropdownMenuItem(
        text = { Text(stringResource(StringsR.string.online_cache_custom_option)) },
        onClick = {
          expanded = false
          onCustom()
        },
      )
    }
  }
}

/** The small editor behind "custom": a dialog, not a 56dp hole in the sheet. */
@Composable
private fun CacheValueDialog(
  title: String,
  initial: String,
  /** Upper bound the run will honour; a bigger number would only be a lie. */
  max: Int?,
  onConfirm: (String) -> Unit,
  onDismiss: () -> Unit,
  hint: String? = null,
) {
  var text by remember { mutableStateOf(initial) }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(title) },
    text = {
      OutlinedTextField(
        modifier = Modifier.fillMaxWidth(),
        value = text,
        onValueChange = { input -> text = input.filter { it.isDigit() }.take(5) },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        supportingText = hint?.let { line -> { Text(line) } },
      )
    },
    confirmButton = {
      TextButton(
        onClick = {
          val typed = text.toIntOrNull()
          onConfirm(
            when {
              typed == null -> text
              max == null -> typed.toString()
              else -> typed.coerceIn(0, max).toString()
            },
          )
        },
        enabled = text.isNotBlank(),
      ) {
        Text(stringResource(StringsR.string.common_dialog_confirm))
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text(stringResource(StringsR.string.common_action_cancel))
      }
    },
  )
}

@Composable
private fun CacheFooter(
  viewState: OnlineCacheDialogState,
  progress: OnlineCacheState?,
  remaining: Int,
  onStartCache: () -> Unit,
  onCancelCache: () -> Unit,
  onClearCache: () -> Unit,
  onClose: () -> Unit,
) {
  val scheme = MaterialTheme.colorScheme
  val downloading = progress?.downloading == true
  val requested = viewState.customCount.toIntOrNull()?.takeIf { it > 0 } ?: viewState.selectedCount
  // the run is clamped to what is left, so the label has to be as well
  val planned = if (remaining > 0) requested.coerceAtMost(remaining) else requested
  Column {
    HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.4f))
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = CACHE_SIDE_PADDING)
        .padding(top = 12.dp, bottom = 8.dp),
      verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
      if (downloading) {
        Button(
          onClick = onCancelCache,
          modifier = Modifier
            .fillMaxWidth()
            .height(50.dp),
          shape = RoundedCornerShape(16.dp),
        ) {
          Text(stringResource(StringsR.string.online_cache_stop))
        }
      } else {
        Button(
          onClick = onStartCache,
          modifier = Modifier
            .fillMaxWidth()
            .height(50.dp),
          shape = RoundedCornerShape(16.dp),
          enabled = !viewState.loading && viewState.info != null && remaining > 0,
        ) {
          Text(
            text = when {
              progress?.stopReason.needsRetry() == true -> stringResource(StringsR.string.online_cache_retry)
              remaining <= 0 -> stringResource(StringsR.string.online_cache_start)
              else -> pluralStringResource(
                StringsR.plurals.online_cache_start_count,
                planned,
                planned,
              )
            },
          )
        }
      }
      Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(
          onClick = onClearCache,
          enabled = downloading || (viewState.info?.cachedCount ?: 0) > 0,
          colors = ButtonDefaults.textButtonColors(contentColor = scheme.error),
        ) {
          Text(
            stringResource(
              if (viewState.clearArmed) {
                StringsR.string.online_cache_clear_confirm
              } else {
                StringsR.string.online_cache_clear
              },
            ),
          )
        }
        Spacer(modifier = Modifier.weight(1f))
        TextButton(onClick = onClose) {
          Text(stringResource(StringsR.string.common_action_close))
        }
      }
    }
  }
}

@Composable
private fun CacheCard(content: @Composable () -> Unit) {
  val scheme = MaterialTheme.colorScheme
  Surface(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(20.dp),
    color = scheme.surfaceContainerHigh,
    // the sheet itself is painted surfaceContainerLow: a card of that same
    // colour is invisible, which is exactly how the old flat layout came back
    border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.4f)),
    content = content,
  )
}

/** "100 集" / "全部" - what the next tap on the primary button will cache. */
@Composable
private fun OnlineCacheDialogState.countSummary(remaining: Int): String {
  val custom = customCount.toIntOrNull()
  return when {
    custom != null && custom > 0 ->
      pluralStringResource(StringsR.plurals.online_cache_count_unit, custom, custom)
    // asking for 500 when 20 are left is still "all of them", not "500"
    remaining > 0 && selectedCount >= remaining ->
      stringResource(StringsR.string.online_cache_all)
    else ->
      pluralStringResource(StringsR.plurals.online_cache_count_unit, selectedCount, selectedCount)
  }
}

@Composable
private fun OnlineCacheDialogState.delaySummary(): String {
  val custom = customDelay.toIntOrNull()
  return when {
    custom != null && custom > 0 -> stringResource(StringsR.string.online_cache_delay_seconds, custom)
    custom != null && custom == 0 -> stringResource(StringsR.string.online_cache_delay_none)
    delaySeconds == 0 -> stringResource(StringsR.string.online_cache_delay_none)
    else -> stringResource(StringsR.string.online_cache_delay_seconds, delaySeconds)
  }
}

/** A stopped run that has work left is worth another tap, not a fresh setup. */
private fun OnlineCacheStopReason?.needsRetry(): Boolean {
  return when (this) {
    OnlineCacheStopReason.TOO_MANY_FAILURES,
    OnlineCacheStopReason.NO_CHAPTER_LIST,
    OnlineCacheStopReason.GAVE_UP,
    OnlineCacheStopReason.ERROR,
    -> true
    else -> false
  }
}

@Composable
private fun OnlineCacheState.statusText(): String = when {
  awaitingConfirmation -> stringResource(StringsR.string.online_cache_awaiting_confirmation)
  downloading -> stringResource(StringsR.string.online_cache_status_running)
  else -> when (stopReason) {
    OnlineCacheStopReason.COMPLETED -> stringResource(StringsR.string.online_cache_status_completed)
    OnlineCacheStopReason.TOO_MANY_FAILURES ->
      stringResource(StringsR.string.online_cache_status_too_many_failures)
    OnlineCacheStopReason.NO_CHAPTER_LIST -> stringResource(StringsR.string.online_cache_status_no_chapters)
    OnlineCacheStopReason.METERED_DECLINED ->
      stringResource(StringsR.string.online_cache_status_metered_declined)
    OnlineCacheStopReason.GAVE_UP -> stringResource(StringsR.string.online_cache_status_gave_up)
    OnlineCacheStopReason.ERROR -> stringResource(StringsR.string.online_cache_status_error)
    null -> stringResource(StringsR.string.online_cache_status_stopped)
  }
}

private fun OnlineCacheState.statusColor(scheme: ColorScheme): Color = when {
  awaitingConfirmation -> scheme.tertiary
  downloading -> scheme.primary
  stopReason == OnlineCacheStopReason.COMPLETED -> scheme.primary
  stopReason == null -> scheme.onSurfaceVariant
  else -> scheme.error
}

/** "本集 12.3 MB / 45.6 MB · 1.2 MB/s", dropping the parts still unknown. */
@Composable
private fun OnlineCacheState.chapterLine(): String {
  val chapterTotal = currentTotalBytes
  val head = when {
    chapterTotal > 0 -> stringResource(
      StringsR.string.online_cache_chapter_progress,
      formatBytes(currentBytes),
      formatBytes(chapterTotal),
    )
    currentBytes > 0 -> stringResource(
      StringsR.string.online_cache_chapter_progress_open,
      formatBytes(currentBytes),
    )
    else -> stringResource(StringsR.string.online_cache_chapter_progress_starting)
  }
  if (bytesPerSecond <= 0L) return head
  return "$head · ${stringResource(StringsR.string.online_cache_speed, formatBytes(bytesPerSecond))}"
}

@Composable
private fun OnlineCacheFailure.cacheTitle(): String {
  // most sources title a chapter "第38集 ...": prefixing the number again would
  // read as "第 38 集 · 第38集 ...", so the prefix only fills in when it is missing
  return if (cacheFailureNeedsChapterPrefix(title, chapterOrder)) {
    stringResource(StringsR.string.online_cache_failure_row, chapterOrder, title)
  } else {
    title
  }
}

/**
 * Whether [title] already states that this is chapter [chapterOrder].
 *
 * A plain `title.contains(order)` lies: "第11集" contains "1", so chapter 1
 * would go out unnumbered, and "第13集" would pass for chapter 3. Only a real
 * chapter number in front counts - "第38集"/"第 38 话" or a bare "38"/"038、".
 */
internal fun cacheFailureNeedsChapterPrefix(
  title: String,
  chapterOrder: Int,
): Boolean {
  if (chapterOrder <= 0) return false
  val wrapped = TITLE_CHAPTER_NUMBER.find(title)?.groupValues?.get(1)?.toIntOrNull()
  if (wrapped == chapterOrder) return false
  val bare = TITLE_LEADING_NUMBER.find(title)?.groupValues?.get(1)?.toIntOrNull()
  return bare != chapterOrder
}

/** "第38集 ..." / "第 38 话 ..." - the source numbers the chapter itself. */
private val TITLE_CHAPTER_NUMBER = Regex("第\\s*(\\d+)\\s*[集话章节期回]")

/** "38 标题" / "038、标题" - numbered, just without the 第/集 wrapper. */
private val TITLE_LEADING_NUMBER = Regex("^0*(\\d+)(?=$|[\\s.、,:：\\-·—])")

/** Cached first: that is the question the user opens this sheet with. */
private val LEGEND_ORDER = listOf(
  OnlineCacheMark.CACHED,
  OnlineCacheMark.PENDING,
  OnlineCacheMark.FAILED,
)

private fun OnlineCacheMark.legendColor(scheme: ColorScheme): Color = when (this) {
  OnlineCacheMark.CACHED -> scheme.primary
  OnlineCacheMark.FAILED -> scheme.error
  OnlineCacheMark.RUNNING, OnlineCacheMark.PENDING -> scheme.outlineVariant
}

private fun OnlineCacheMark.labelRes(): Int = when (this) {
  OnlineCacheMark.CACHED -> StringsR.string.online_cache_legend_cached
  // the chapter in flight is drawn as a pending cell, so it is labelled as one
  OnlineCacheMark.RUNNING, OnlineCacheMark.PENDING -> StringsR.string.online_cache_legend_pending
  OnlineCacheMark.FAILED -> StringsR.string.online_cache_legend_failed
}

private fun OnlineCacheFailureReason.labelRes(): Int = when (this) {
  OnlineCacheFailureReason.RESOLVE -> StringsR.string.online_cache_reason_resolve
  OnlineCacheFailureReason.DOWNLOAD -> StringsR.string.online_cache_reason_download
  OnlineCacheFailureReason.NOT_AUDIO -> StringsR.string.online_cache_reason_not_audio
  OnlineCacheFailureReason.INCOMPLETE -> StringsR.string.online_cache_reason_incomplete
}

/**
 * 与播放器的取流失败弹窗同一套处理：剥掉异常类型前缀和 JS 调用栈，只把源
 * 自己说的话留给用户。缓存和播放碰到的失败是同一种，呈现也该是同一种。
 */
private fun rawCacheErrorText(detail: String): String {
  var text = detail.trim()
  // 每轮至少去掉一个前缀字符，所以一定会停；剥到不再匹配为止。
  while (true) {
    val stripped = text.replace(FRAMEWORK_PREFIX, "").trim()
    if (stripped == text || stripped.isEmpty()) break
    text = stripped
  }
  return text.lines()
    .filterNot { JS_STACK_FRAME.containsMatchIn(it) }
    .joinToString("\n")
    .trim()
}

/** `Error: ...` / `java.io.IOException: ...` / `QuickJsException: ...` */
private val FRAMEWORK_PREFIX = Regex("^([\\w.$]*(Exception|Error|Failure|Throwable)|Error):\\s*")

/** `    at audio (l.js:154:37)` / `at com.x.Y(Z.kt:12)` / `at l.js:12:5` - and only those. */
private val JS_STACK_FRAME = Regex(
  "^\\s*at\\s+\\S.*\\([^()]*:\\d+(?::\\d+)?\\)\\s*$|^\\s*at\\s+\\S+:\\d+:\\d+\\s*$",
)

private val CACHE_SIDE_PADDING = 20.dp

/** 原始返回正文的最大高度，超出的在弹窗内滚动而不是被截断。 */
private val MAX_FAILURE_DETAIL_HEIGHT = 220.dp
