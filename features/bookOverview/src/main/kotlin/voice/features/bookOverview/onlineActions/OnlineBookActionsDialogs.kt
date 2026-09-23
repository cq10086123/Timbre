package voice.features.bookOverview.onlineActions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import voice.core.online.OnlineCacheState
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
 * Caching needs more room than a dialog has and, above all, two things that
 * have to stay visible: the live progress and the clear action. Both sit in
 * the fixed footer below the scrollable options - down in an alert dialog's
 * text slot they were the first thing a phone screen clipped away.
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
            // while a job runs the options would only collect dust: the sheet
            // stays short and the live progress keeps the stage
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
      // the footer is what the old alert dialog clipped: progress and the
      // clear action never scroll out of sight
      if (progress != null &&
        (progress.downloading || progress.awaitingConfirmation || progress.failed > 0)
      ) {
        CacheProgress(
          progress = progress,
          modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(top = 12.dp),
        )
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
            Text(stringResource(StringsR.string.online_cache_start))
          }
        }
      }
      Spacer(modifier = Modifier.height(16.dp))
    }
  }
}

@Composable
private fun CacheProgress(
  progress: OnlineCacheState,
  modifier: Modifier = Modifier,
) {
  val fraction = if (progress.total <= 0) {
    0f
  } else {
    (progress.done.toFloat() / progress.total).coerceIn(0f, 1f)
  }
  val percent = (fraction * 100).toInt()
  Column(modifier = modifier) {
    // a bar and a current episode only mean "work is running": once the job is
    // done the count in the sheet above is the truth, and a frozen leftover
    // bar labelled "caching" would claim the opposite. Failures stay visible
    // either way - they are the reason to start another run
    if (progress.downloading || progress.awaitingConfirmation) {
      LinearProgressIndicator(
        progress = { fraction },
        modifier = Modifier.fillMaxWidth(),
      )
      Text(
        modifier = Modifier.padding(top = 6.dp),
        text = stringResource(StringsR.string.online_cache_downloading, progress.done, progress.total, percent),
        style = MaterialTheme.typography.bodySmall,
      )
    }
    if (progress.failed > 0) {
      Text(
        modifier = Modifier.padding(top = 6.dp),
        text = stringResource(StringsR.string.online_cache_failed_count, progress.failed),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
      )
    }
    when {
      progress.awaitingConfirmation -> {
        Text(
          modifier = Modifier.padding(top = 6.dp),
          text = stringResource(StringsR.string.online_cache_awaiting_confirmation),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.error,
        )
      }
      progress.downloading && progress.currentTitle.isNotBlank() -> {
        Text(
          modifier = Modifier.padding(top = 6.dp),
          text = progress.currentTitle,
          style = MaterialTheme.typography.bodySmall,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
  }
}
