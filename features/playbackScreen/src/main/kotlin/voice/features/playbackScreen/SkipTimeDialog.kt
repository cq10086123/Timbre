package voice.features.playbackScreen

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import voice.core.strings.R as StringsR

/** Granularity of the intro/outro skip, in whole seconds. */
internal const val SKIP_TIME_STEP_SECONDS = 5

/** Upper bound of the intro/outro skip. 0 means off. */
internal const val SKIP_TIME_MAX_SECONDS = 120

internal const val MILLIS_PER_SECOND = 1000L

@Composable
internal fun SkipIntroDialog(
  dialogState: BookPlayDialogViewState.SkipIntroDialog,
  viewModel: BookPlayViewModel,
) {
  SkipTimeDialog(
    titleRes = StringsR.string.playback_option_skip_intro,
    seconds = dialogState.skipIntroSeconds,
    onValueChange = viewModel::onSkipIntroChanged,
    onDismiss = viewModel::dismissDialog,
  )
}

@Composable
internal fun SkipOutroDialog(
  dialogState: BookPlayDialogViewState.SkipOutroDialog,
  viewModel: BookPlayViewModel,
) {
  SkipTimeDialog(
    titleRes = StringsR.string.playback_option_skip_outro,
    seconds = dialogState.skipOutroSeconds,
    onValueChange = viewModel::onSkipOutroChanged,
    onDismiss = viewModel::dismissDialog,
  )
}

@Composable
private fun SkipTimeDialog(
  @StringRes titleRes: Int,
  seconds: Long,
  onValueChange: (Long) -> Unit,
  onDismiss: () -> Unit,
) {
  val label = stringResource(id = titleRes)
  AlertDialog(
    onDismissRequest = onDismiss,
    confirmButton = {},
    title = {
      Text(label)
    },
    text = {
      Column {
        Text(label + ": " + skipTimeFormatted(seconds))
        Slider(
          valueRange = 0F..SKIP_TIME_MAX_SECONDS.toFloat(),
          steps = SKIP_TIME_MAX_SECONDS / SKIP_TIME_STEP_SECONDS - 1,
          value = seconds.toFloat().coerceIn(0F, SKIP_TIME_MAX_SECONDS.toFloat()),
          onValueChange = {
            onValueChange(it.toLong())
          },
        )
      }
    },
  )
}

@Composable
private fun skipTimeFormatted(seconds: Long): String {
  return if (seconds <= 0L) {
    stringResource(id = StringsR.string.playback_skip_off)
  } else {
    stringResource(id = StringsR.string.playback_skip_seconds, seconds.toInt())
  }
}
