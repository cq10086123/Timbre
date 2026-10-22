package voice.core.ui

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
import androidx.compose.ui.unit.dp
import voice.core.strings.R as StringsR

/**
 * Shows the text the maintainers put into `announcement.txt`. The sheet is
 * plain text on purpose - whatever is in the file is what the user reads.
 */
@Composable
fun AnnouncementDialog(
  announcement: String,
  onKnowClick: () -> Unit,
  onNeverShowClick: () -> Unit,
) {
  AlertDialog(
    onDismissRequest = onKnowClick,
    title = {
      Text(text = stringResource(id = StringsR.string.announcement_dialog_title))
    },
    text = {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .heightIn(max = 360.dp)
          .verticalScroll(rememberScrollState()),
      ) {
        Text(
          text = announcement,
          style = MaterialTheme.typography.bodyMedium,
        )
      }
    },
    dismissButton = {
      TextButton(onClick = onNeverShowClick) {
        Text(text = stringResource(id = StringsR.string.announcement_dialog_never))
      }
    },
    confirmButton = {
      TextButton(onClick = onKnowClick) {
        Text(text = stringResource(id = StringsR.string.announcement_dialog_know))
      }
    },
  )
}
