package voice.features.settings.views

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import voice.core.ui.icons.VoiceIcons
import kotlin.math.roundToInt
import voice.core.strings.R as StringsR

/**
 * Settings rows of the online playback preload - only online books preload
 * anything, so the whole group is meaningless for local and WebDav playback.
 */
@Composable
internal fun OnlinePreloadEnabledRow(
  checked: Boolean,
  onCheck: (Boolean) -> Unit,
) {
  ListItem(
    modifier = Modifier
      .clickable { onCheck(!checked) }
      .fillMaxWidth(),
    leadingContent = {
      Icon(
        imageVector = VoiceIcons.Download,
        contentDescription = stringResource(StringsR.string.settings_playback_online_preload_title),
      )
    },
    supportingContent = {
      Text(stringResource(StringsR.string.settings_playback_online_preload_summary))
    },
    trailingContent = {
      Switch(
        checked = checked,
        onCheckedChange = onCheck,
      )
    },
  ) {
    Text(stringResource(StringsR.string.settings_playback_online_preload_enabled_title))
  }
}

@Composable
internal fun OnlinePreloadValueRow(
  titleRes: Int,
  valueText: String,
  onClick: () -> Unit,
) {
  ListItem(
    modifier = Modifier
      .clickable { onClick() }
      .fillMaxWidth(),
    supportingContent = {
      Text(valueText, style = MaterialTheme.typography.bodyLarge)
    },
  ) {
    Text(stringResource(titleRes))
  }
}

/**
 * Generic slider dialog of the preload settings: one number, a value line and
 * an explanation of what the number does.
 */
@Composable
internal fun OnlinePreloadSliderDialog(
  titleRes: Int,
  explanationRes: Int,
  valueFormatRes: Int,
  currentValue: Int,
  min: Int,
  max: Int,
  onConfirm: (Int) -> Unit,
  onDismiss: () -> Unit,
) {
  var sliderValue by remember { mutableFloatStateOf(currentValue.toFloat()) }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = {
      Text(stringResource(titleRes))
    },
    text = {
      Column {
        Text(
          modifier = Modifier.padding(bottom = 8.dp),
          text = stringResource(explanationRes),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
          text = stringResource(valueFormatRes, sliderValue.roundToInt()),
          style = MaterialTheme.typography.bodyLarge,
        )
        Slider(
          value = sliderValue,
          valueRange = min.toFloat()..max.toFloat(),
          onValueChange = { sliderValue = it },
        )
      }
    },
    confirmButton = {
      TextButton(
        onClick = {
          onConfirm(sliderValue.roundToInt())
          onDismiss()
        },
      ) {
        Text(stringResource(StringsR.string.common_dialog_confirm))
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text(stringResource(StringsR.string.common_dialog_cancel))
      }
    },
  )
}
