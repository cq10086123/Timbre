package voice.features.bookOverview.views

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import voice.core.update.UpdateAvailable
import voice.core.strings.R as StringsR

@Composable
internal fun UpdateDialog(
  update: UpdateAvailable,
  onUpdateClick: () -> Unit,
  onDismissClick: () -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDismissClick,
    title = {
      Text(text = stringResource(id = StringsR.string.update_dialog_title))
    },
    text = {
      Text(text = stringResource(id = StringsR.string.update_dialog_message, update.versionName))
    },
    dismissButton = {
      TextButton(onClick = onDismissClick) {
        Text(text = stringResource(id = StringsR.string.common_dialog_cancel))
      }
    },
    confirmButton = {
      TextButton(onClick = onUpdateClick) {
        Text(text = stringResource(id = StringsR.string.update_dialog_confirm))
      }
    },
  )
}
