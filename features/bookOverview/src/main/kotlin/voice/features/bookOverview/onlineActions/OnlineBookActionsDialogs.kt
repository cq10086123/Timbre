package voice.features.bookOverview.onlineActions

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue.Expanded
import androidx.compose.material3.SheetValue.Hidden
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import voice.core.online.OnlineCacheFailureReason
import voice.core.online.OnlineCacheMark
import voice.core.online.OnlineCacheState
import voice.core.online.OnlineCacheStopReason
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
 * Caching needs more room than a dialog has and, above all, the live state has
 * to stay visible while the options scroll. The headline (what is happening,
 * overall progress, the episode in flight) is therefore pinned above the
 * scrollable part, and the fixed footer keeps the clear action reachable.
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
  val downloading = progress?.downloading == true
  val sheetState = rememberBottomSheetState(
    initialValue = Hidden,
    enabledValues = setOf(Hidden, Expanded),
  )
  val scope = rememberCoroutineScope()
  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
  ) {
    Column(modifier = Modifier.fillMaxWidth()) {
      Text(
        modifier = Modifier
          .padding(horizontal = 24.dp)
          .fillMaxWidth(),
        text = info?.title?.takeIf { it.isNotBlank() }
          ?: stringResource(StringsR.string.online_cache_title),
        style = MaterialTheme.typography.headlineSmall,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
      if (progress != null) {
        CacheHeadline(
          progress = progress,
          modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(top = 12.dp),
        )
      }
      Column(
        modifier = Modifier
          .weight(weight = 1f, fill = false)
          .verticalScroll(rememberScrollState())
          .padding(horizontal = 24.dp)
          .padding(top = 12.dp),
      ) {
        when {
          viewState.loading -> {
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
              horizontalArrangement = Arrangement.Center,
            ) {
              CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
            }
          }
          info == null -> {
            Text(stringResource(StringsR.string.search_online_error))
          }
          else -> {
            Text(
              text = stringResource(
                StringsR.string.online_cache_cached,
                info.cachedCount,
                info.totalChapters,
                formatBytes(info.cachedBytes),
              ),
              style = MaterialTheme.typography.bodyMedium,
            )
            if (info.totalChapters > 0) {
              Text(
                modifier = Modifier.padding(top = 4.dp),
                text = stringResource(StringsR.string.online_cache_from_current, info.currentIndex + 1),
                style = MaterialTheme.typography.bodySmall,
              )
            }
            Text(
              modifier = Modifier.padding(top = 4.dp),
              text = stringResource(StringsR.string.online_cache_offline_hint),
              style = MaterialTheme.typography.bodySmall,
            )
            if (progress != null && (progress.marks.isNotEmpty() || progress.failures.isNotEmpty())) {
              Spacer(modifier = Modifier.height(12.dp))
              CacheDetails(progress = progress)
            }
            // while a job runs the options would only collect dust: the sheet
            // stays short and the live state keeps the stage
            if (!downloading) {
              Spacer(modifier = Modifier.height(12.dp))
              Text(
                text = stringResource(StringsR.string.online_cache_count_label),
                style = MaterialTheme.typography.bodyMedium,
              )
              FlowRow(
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
              ) {
                CACHE_COUNT_PRESETS.forEach { preset ->
                  FilterChip(
                    selected = viewState.customCount.isBlank() && viewState.selectedCount == preset,
                    onClick = { onSelectCount(preset) },
                    label = { Text(preset.toString()) },
                  )
                }
                if (remaining > 0) {
                  FilterChip(
                    // when the remainder happens to be a preset its own chip
                    // lights up instead, so exactly one chip is ever selected
                    selected = viewState.customCount.isBlank() &&
                      viewState.selectedCount == remaining &&
                      remaining !in CACHE_COUNT_PRESETS,
                    onClick = { onSelectCount(remaining) },
                    label = { Text(stringResource(StringsR.string.online_cache_all)) },
                  )
                }
              }
              OutlinedTextField(
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(top = 8.dp),
                value = viewState.customCount,
                onValueChange = onCustomCountChange,
                label = { Text(stringResource(StringsR.string.online_cache_count_label)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
              )
              Spacer(modifier = Modifier.height(12.dp))
              Text(
                text = stringResource(StringsR.string.online_cache_delay_label),
                style = MaterialTheme.typography.bodyMedium,
              )
              Text(
                modifier = Modifier.padding(top = 2.dp),
                text = stringResource(StringsR.string.online_cache_delay_hint),
                style = MaterialTheme.typography.bodySmall,
              )
              FlowRow(
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
              ) {
                CACHE_DELAY_PRESETS.forEach { preset ->
                  FilterChip(
                    selected = viewState.customDelay.isBlank() && viewState.delaySeconds == preset,
                    onClick = { onSelectDelay(preset) },
                    label = {
                      Text(
                        if (preset == 0) {
                          stringResource(StringsR.string.online_cache_delay_none)
                        } else {
                          stringResource(StringsR.string.online_cache_delay_seconds, preset)
                        },
                      )
                    },
                  )
                }
              }
              OutlinedTextField(
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(top = 8.dp),
                value = viewState.customDelay,
                onValueChange = onCustomDelayChange,
                label = { Text(stringResource(StringsR.string.online_cache_delay_seconds_custom)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
              )
            }
          }
        }
      }
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 16.dp)
          .padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        TextButton(
          onClick = onClearCache,
          enabled = progress?.downloading == true || (info?.cachedCount ?: 0) > 0,
          colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
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
        TextButton(
          onClick = {
            // close via the sheet state, so the panel slides away instead of
            // vanishing the moment the state drops its dialog
            scope.launch {
              sheetState.hide()
              onDismiss()
            }
          },
        ) {
          Text(stringResource(StringsR.string.common_action_close))
        }
        if (downloading) {
          Button(onClick = onCancelCache) {
            Text(stringResource(StringsR.string.online_cache_stop))
          }
        } else {
          Button(
            onClick = onStartCache,
            enabled = !viewState.loading && info != null && remaining > 0,
          ) {
            Text(
              stringResource(
                if (progress?.stopReason.needsRetry()) {
                  StringsR.string.online_cache_retry
                } else {
                  StringsR.string.online_cache_start
                },
              ),
            )
          }
        }
      }
      Spacer(modifier = Modifier.height(16.dp))
    }
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

/**
 * The pinned part of the sheet: what the job is doing, how far the window is
 * and how far the episode in flight is. It never scrolls away, so a running
 * cache is always described by the same four lines.
 */
@Composable
private fun CacheHeadline(
  progress: OnlineCacheState,
  modifier: Modifier = Modifier,
) {
  val scheme = MaterialTheme.colorScheme
  Column(
    modifier = modifier,
    verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Text(
      text = progress.statusText(),
      style = MaterialTheme.typography.labelLarge,
      color = progress.statusColor(scheme),
    )
    if (progress.total > 0) {
      val fraction = (progress.done.toFloat() / progress.total).coerceIn(0f, 1f)
      LinearProgressIndicator(
        progress = { fraction },
        modifier = Modifier.fillMaxWidth(),
      )
      Text(
        text = stringResource(
          StringsR.string.online_cache_progress_count,
          progress.done,
          progress.total,
          (fraction * 100).toInt(),
        ),
        style = MaterialTheme.typography.bodySmall,
      )
    }
    if (progress.downloading && progress.currentTitle.isNotBlank()) {
      Text(
        text = stringResource(StringsR.string.online_cache_current_chapter, progress.currentTitle),
        style = MaterialTheme.typography.bodySmall,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
      // the episode size is what separates "the source is slow" from "this
      // episode is just huge", so it gets its own bar
      val chapterTotal = progress.currentTotalBytes
      if (chapterTotal > 0) {
        LinearProgressIndicator(
          progress = { (progress.currentBytes.toFloat() / chapterTotal).coerceIn(0f, 1f) },
          modifier = Modifier.fillMaxWidth(),
        )
      } else {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
      }
      Text(
        text = progress.chapterLine(),
        style = MaterialTheme.typography.bodySmall,
      )
    }
  }
}

/** The chapter map and the failure list: the "where is it stuck" part. */
@Composable
private fun CacheDetails(progress: OnlineCacheState) {
  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    if (progress.marks.isNotEmpty()) {
      ChapterMap(progress)
    }
    if (progress.failures.isNotEmpty()) {
      FailureList(progress)
    }
  }
}

/**
 * One square per episode of the window: green once cached, red when it failed,
 * highlighted while it is in flight. A run that dies at episode 7 of 50 looks
 * exactly like that here instead of looking like "nothing happened".
 */
@Composable
private fun ChapterMap(progress: OnlineCacheState) {
  val scheme = MaterialTheme.colorScheme
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    FlowRow(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(3.dp),
      verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
      progress.marks.forEach { mark ->
        Box(
          modifier = Modifier
            .size(9.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(mark.color(scheme)),
        )
      }
    }
    FlowRow(
      horizontalArrangement = Arrangement.spacedBy(12.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
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
private fun LegendItem(
  mark: OnlineCacheMark,
  scheme: ColorScheme,
) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    Box(
      modifier = Modifier
        .size(7.dp)
        .clip(RoundedCornerShape(2.dp))
        .background(mark.color(scheme)),
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
 * server returned no audio, the link may have expired" is.
 */
@Composable
private fun FailureList(progress: OnlineCacheState) {
  val scheme = MaterialTheme.colorScheme
  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Text(
      text = stringResource(StringsR.string.online_cache_failures_title, progress.failed),
      style = MaterialTheme.typography.labelMedium,
      color = scheme.error,
    )
    progress.failures.forEach { failure ->
      Column {
        Text(
          text = failure.title,
          style = MaterialTheme.typography.bodySmall,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          text = stringResource(failure.reason.labelRes()),
          style = MaterialTheme.typography.bodySmall,
          color = scheme.error,
        )
      }
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

private fun OnlineCacheMark.color(scheme: ColorScheme): Color = when (this) {
  OnlineCacheMark.PENDING -> scheme.outlineVariant
  OnlineCacheMark.RUNNING -> scheme.tertiary
  OnlineCacheMark.CACHED -> scheme.primary
  OnlineCacheMark.FAILED -> scheme.error
}

private fun OnlineCacheMark.labelRes(): Int = when (this) {
  OnlineCacheMark.CACHED -> StringsR.string.online_cache_legend_cached
  OnlineCacheMark.RUNNING -> StringsR.string.online_cache_legend_running
  OnlineCacheMark.FAILED -> StringsR.string.online_cache_legend_failed
  OnlineCacheMark.PENDING -> StringsR.string.online_cache_legend_pending
}

private fun OnlineCacheFailureReason.labelRes(): Int = when (this) {
  OnlineCacheFailureReason.RESOLVE -> StringsR.string.online_cache_reason_resolve
  OnlineCacheFailureReason.DOWNLOAD -> StringsR.string.online_cache_reason_download
  OnlineCacheFailureReason.NOT_AUDIO -> StringsR.string.online_cache_reason_not_audio
  OnlineCacheFailureReason.INCOMPLETE -> StringsR.string.online_cache_reason_incomplete
}

/** Cached first: that is the question the user opens this sheet with. */
private val LEGEND_ORDER = listOf(
  OnlineCacheMark.CACHED,
  OnlineCacheMark.RUNNING,
  OnlineCacheMark.FAILED,
  OnlineCacheMark.PENDING,
)
