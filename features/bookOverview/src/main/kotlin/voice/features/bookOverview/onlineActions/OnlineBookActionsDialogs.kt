package voice.features.bookOverview.onlineActions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import voice.core.online.OnlineCacheState
import voice.core.online.OnlineChapterRefreshResult
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

@Composable
internal fun OnlineCacheDialog(
  viewState: OnlineCacheDialogState,
  progress: OnlineCacheState?,
  onSelectCount: (Int) -> Unit,
  onCustomCountChange: (String) -> Unit,
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
  AlertDialog(
    onDismissRequest = onDismiss,
    title = {
      Text(
        text = info?.title?.takeIf { it.isNotBlank() }
          ?: stringResource(StringsR.string.online_cache_title),
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
    },
    text = {
      Column(modifier = Modifier.fillMaxWidth()) {
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
            Spacer(modifier = Modifier.height(12.dp))
            Text(
              text = stringResource(StringsR.string.online_cache_count_label),
              style = MaterialTheme.typography.bodyMedium,
            )
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
              horizontalArrangement = Arrangement.spacedBy(8.dp),
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
            if (progress != null && (progress.total > 0 || downloading)) {
              Spacer(modifier = Modifier.height(12.dp))
              if (downloading) {
                LinearProgressIndicator(
                  progress = { progress.done / progress.total.coerceAtLeast(1).toFloat() },
                  modifier = Modifier.fillMaxWidth(),
                )
                Text(
                  modifier = Modifier.padding(top = 6.dp),
                  text = stringResource(StringsR.string.online_cache_downloading, progress.done, progress.total),
                  style = MaterialTheme.typography.bodySmall,
                )
                if (progress.currentTitle.isNotBlank()) {
                  Text(
                    text = progress.currentTitle,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                  )
                }
              } else if (progress.failed > 0) {
                Text(
                  text = stringResource(StringsR.string.online_cache_failed_count, progress.failed),
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.error,
                )
              }
            }
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(
              onClick = onClearCache,
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
          }
        }
      }
    },
    confirmButton = {
      if (downloading) {
        Button(onClick = onCancelCache) {
          Text(stringResource(StringsR.string.common_dialog_cancel))
        }
      } else {
        Button(
          onClick = onStartCache,
          enabled = !viewState.loading && info != null && remaining > 0,
        ) {
          Text(stringResource(StringsR.string.online_cache_start))
        }
      }
    },
    dismissButton = {
      // while a job runs the confirm button cancels it; the dialog itself
      // still closes via back press or outside tap and the job continues
      if (!downloading) {
        TextButton(onClick = onDismiss) {
          Text(stringResource(StringsR.string.common_dialog_cancel))
        }
      }
    },
  )
}

private fun formatBytes(bytes: Long): String {
  if (bytes <= 0L) return "0 B"
  val units = arrayOf("B", "KB", "MB", "GB")
  var value = bytes.toDouble()
  var unit = 0
  while (value >= 1024 && unit < units.lastIndex) {
    value /= 1024
    unit++
  }
  return if (unit == 0) {
    "${value.toLong()} ${units[unit]}"
  } else {
    "${(value * 10).toLong() / 10.0} ${units[unit]}"
  }
}
