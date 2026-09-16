package voice.features.playbackScreen

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import voice.core.strings.R as StringsR

/** Upper bound of the intro/outro skip, in whole seconds. 0 means off. */
internal const val SKIP_TIME_MAX_SECONDS = 120

internal const val MILLIS_PER_SECOND = 1000L

@Composable
internal fun SkipDialog(
  dialogState: BookPlayDialogViewState.SkipDialog,
  viewModel: BookPlayViewModel,
) {
  AlertDialog(
    onDismissRequest = viewModel::dismissDialog,
    confirmButton = {},
    title = {
      Text(stringResource(id = StringsR.string.playback_option_skip_intro_outro))
    },
    text = {
      Column {
        SkipTimeRow(
          labelRes = StringsR.string.playback_option_skip_intro,
          seconds = dialogState.skipIntroSeconds,
          onValueChange = viewModel::onSkipIntroChanged,
        )
        Spacer(modifier = Modifier.height(16.dp))
        SkipTimeRow(
          labelRes = StringsR.string.playback_option_skip_outro,
          seconds = dialogState.skipOutroSeconds,
          onValueChange = viewModel::onSkipOutroChanged,
        )
      }
    },
  )
}

@Composable
private fun SkipTimeRow(
  @StringRes labelRes: Int,
  seconds: Long,
  onValueChange: (Long) -> Unit,
) {
  Column {
    Text(text = stringResource(id = labelRes) + ": " + skipTimeFormatted(seconds))
    // No `steps`: with a one second granularity over two minutes the tick marks would blur into a
    // solid line, so the thumb moves freely and the value snaps to whole seconds instead.
    Slider(
      value = seconds.toFloat().coerceIn(0F, SKIP_TIME_MAX_SECONDS.toFloat()),
      valueRange = 0F..SKIP_TIME_MAX_SECONDS.toFloat(),
      onValueChange = { value ->
        onValueChange(
          value
            .coerceIn(0F, SKIP_TIME_MAX_SECONDS.toFloat())
            .roundToInt()
            .toLong(),
        )
      },
    )
  }
}

@Composable
private fun skipTimeFormatted(seconds: Long): String {
  return if (seconds <= 0L) {
    stringResource(id = StringsR.string.playback_skip_off)
  } else {
    stringResource(id = StringsR.string.playback_skip_seconds, seconds.toInt())
  }
}
