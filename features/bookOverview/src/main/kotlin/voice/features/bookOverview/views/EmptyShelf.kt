package voice.features.bookOverview.views

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import voice.core.ui.icons.VoiceIcons
import voice.core.strings.R as StringsR

@Composable
internal fun EmptyShelf(
  onAddClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier = modifier
      .fillMaxSize()
      .padding(32.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    Icon(
      imageVector = VoiceIcons.LibraryBooks,
      contentDescription = null,
      modifier = Modifier.size(96.dp),
      tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
    )
    Text(
      modifier = Modifier.padding(top = 24.dp),
      text = stringResource(StringsR.string.library_empty_add_first_book),
      style = MaterialTheme.typography.titleMedium,
      textAlign = TextAlign.Center,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Button(
      modifier = Modifier.padding(top = 24.dp),
      onClick = onAddClick,
    ) {
      Icon(
        imageVector = VoiceIcons.Add,
        contentDescription = null,
        modifier = Modifier.size(ButtonDefaults.IconSize),
      )
      Spacer(Modifier.size(ButtonDefaults.IconSpacing))
      Text(text = stringResource(StringsR.string.common_action_add))
    }
  }
}
