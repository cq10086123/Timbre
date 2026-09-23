package voice.app.cache

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import voice.core.online.OnlineBookCacheManager
import voice.core.strings.R as StringsR

/**
 * The metered question of the cache manager, hosted at app level so it shows
 * on every screen: a cache job resumed at app start may well find the user on
 * the player, and without an answer the job waits - the point of the question
 * is that mobile data is never spent silently. Dismissing it (back, outside
 * tap) means no, exactly like the cancel button.
 */
@Composable
fun OnlineCacheMeteredPrompt(manager: OnlineBookCacheManager) {
  val confirmation = manager.meteredConfirmation.collectAsState().value ?: return
  AlertDialog(
    onDismissRequest = manager::declineMeteredCache,
    title = { Text(stringResource(StringsR.string.online_cache_metered_title)) },
    text = {
      Column {
        Text(stringResource(StringsR.string.online_cache_metered_message))
        if (confirmation.title.isNotBlank()) {
          Text(
            modifier = Modifier.padding(top = 8.dp),
            text = stringResource(StringsR.string.online_cache_metered_book, confirmation.title),
            style = MaterialTheme.typography.bodyMedium,
          )
        }
        Text(
          modifier = Modifier.padding(top = 4.dp),
          text = stringResource(StringsR.string.online_cache_metered_chapters, confirmation.chapters),
          style = MaterialTheme.typography.bodyMedium,
        )
      }
    },
    confirmButton = {
      Button(onClick = manager::confirmMeteredCache) {
        Text(stringResource(StringsR.string.online_cache_metered_confirm))
      }
    },
    dismissButton = {
      TextButton(onClick = manager::declineMeteredCache) {
        Text(stringResource(StringsR.string.common_dialog_cancel))
      }
    },
  )
}
