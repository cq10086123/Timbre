package voice.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import voice.core.update.UpdateAvailable
import voice.core.update.formatReleaseNotes
import voice.core.strings.R as StringsR

/**
 * Shows what changed in the available release. Shared by the book overview
 * screen (where it appears on its own) and the settings screen (where the
 * version row opens it on demand).
 */
@Composable
fun UpdateDialog(
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
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = stringResource(id = StringsR.string.update_dialog_message, update.versionName))
        val notes = update.releaseNotes
        if (!notes.isNullOrBlank()) {
          Text(
            text = stringResource(id = StringsR.string.update_dialog_notes_title),
            style = MaterialTheme.typography.titleSmall,
          )
          Column(
            modifier = Modifier
              .fillMaxWidth()
              .heightIn(max = 280.dp)
              .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
          ) {
            formatReleaseNotes(notes).forEach { line ->
              Text(
                text = line.text,
                style = if (line.isHeading) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
                fontWeight = if (line.isHeading) FontWeight.SemiBold else null,
              )
            }
          }
        }
      }
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
