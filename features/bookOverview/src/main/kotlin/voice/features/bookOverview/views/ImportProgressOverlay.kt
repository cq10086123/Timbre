package voice.features.bookOverview.views

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import voice.core.scanner.ScanProgress
import voice.core.strings.R as StringsR

@Composable
internal fun ImportProgressOverlay(
  visible: Boolean,
  progress: ScanProgress?,
  modifier: Modifier = Modifier,
) {
  AnimatedVisibility(
    visible = visible,
    enter = fadeIn() + slideInVertically { it / 2 },
    exit = fadeOut() + slideOutVertically { it / 2 },
    modifier = modifier,
  ) {
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .navigationBarsPadding()
        .padding(16.dp),
      contentAlignment = Alignment.BottomCenter,
    ) {
      Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
      ) {
        Column(
          modifier = Modifier.padding(16.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Text(
            text = if (progress != null) {
              pluralStringResource(
                StringsR.plurals.library_import_progress,
                progress.chaptersTotal,
                progress.chaptersScanned,
                progress.chaptersTotal,
              )
            } else {
              stringResource(StringsR.string.library_import_preparing)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
          )
          if (progress != null) {
            LinearProgressIndicator(
              progress = { progress.fraction.coerceIn(0f, 1f) },
              modifier = Modifier.fillMaxWidth(),
            )
          } else {
            LinearProgressIndicator(
              modifier = Modifier.fillMaxWidth(),
            )
          }
        }
      }
    }
  }
}
