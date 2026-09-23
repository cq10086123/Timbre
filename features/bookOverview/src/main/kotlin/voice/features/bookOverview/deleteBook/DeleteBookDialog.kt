package voice.features.bookOverview.deleteBook

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import voice.features.bookOverview.formatBytes
import voice.core.strings.R as StringsR

@Composable
internal fun DeleteBookDialog(
  viewState: DeleteBookViewState,
  onDismiss: () -> Unit,
  onConfirmDeletion: () -> Unit,
  onDeleteCheckBoxCheck: (Boolean) -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = {
      Text(stringResource(StringsR.string.book_delete_dialog_title))
    },
    confirmButton = {
      Button(
        onClick = onConfirmDeletion,
        // removing a book from the shelf keeps the files and is therefore not
        // destructive. The error colours and the required checkbox only apply
        // when the user also asks for the files to be deleted.
        colors = if (viewState.deleteCheckBoxChecked) {
          ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.error,
          )
        } else {
          ButtonDefaults.buttonColors()
        },
      ) {
        Text(stringResource(id = StringsR.string.common_action_delete))
      }
    },
    dismissButton = {
      TextButton(
        onClick = onDismiss,
      ) {
        Text(stringResource(id = StringsR.string.common_dialog_cancel))
      }
    },
    text = {
      Column {
        // an online book has no files on the device, but deleting it drops its
        // manually cached episodes: the generic "the audio stays on your
        // device" message would be a lie here
        Text(
          stringResource(
            id = if (viewState.isOnlineBook) {
              StringsR.string.book_delete_dialog_message_online
            } else {
              StringsR.string.book_delete_dialog_message
            },
          ),
        )

        if (viewState.fileToDelete.isNotBlank()) {
          Spacer(modifier = Modifier.heightIn(8.dp))
          Text(viewState.fileToDelete, style = MaterialTheme.typography.bodyLarge)
        }

        // how much of the cache disappears with the book, instead of leaving
        // that in the dark
        if (viewState.cachedChapters > 0) {
          Spacer(modifier = Modifier.heightIn(8.dp))
          Text(
            text = stringResource(
              id = StringsR.string.book_delete_dialog_cached,
              viewState.cachedChapters,
              formatBytes(viewState.cachedBytes),
            ),
            style = MaterialTheme.typography.bodyMedium,
          )
        }

        // remote (webdav) and online books have no local files: offering their
        // deletion would promise something the app cannot do. Their cached
        // episodes are removed either way, as the message above says.
        if (viewState.canDeleteFiles) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
              .padding(top = 8.dp)
              .fillMaxWidth()
              .clickable {
                onDeleteCheckBoxCheck(!viewState.deleteCheckBoxChecked)
              },
          ) {
            Checkbox(
              checked = viewState.deleteCheckBoxChecked,
              onCheckedChange = onDeleteCheckBoxCheck,
            )
            Text(stringResource(id = StringsR.string.book_delete_dialog_confirm_files))
          }
        }
      }
    },
  )
}
