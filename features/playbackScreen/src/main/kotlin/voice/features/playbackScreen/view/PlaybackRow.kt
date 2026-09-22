package voice.features.playbackScreen.view

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import voice.core.ui.PlayButton
import voice.core.ui.playButtonSharedBoundsModifier

@Composable
internal fun PlaybackRow(
  playing: Boolean,
  onPlayClick: () -> Unit,
  onRewindClick: () -> Unit,
  onFastForwardClick: () -> Unit,
  loading: Boolean = false,
) {
  Row(
    modifier = Modifier
      .fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.Center,
  ) {
    SkipButton(forward = false, onClick = onRewindClick)
    Spacer(modifier = Modifier.size(16.dp))

    // the loading ring is wider than the button: the box keeps its size, so
    // showing and hiding the ring cannot move the button - and with it every
    // row above - around
    Box(
      modifier = Modifier.size(88.dp),
      contentAlignment = Alignment.Center,
    ) {
      PlayButton(
        playing = playing,
        fabSize = 80.dp,
        iconSize = 36.dp,
        onPlayClick = onPlayClick,
        sharedElementModifier = Modifier.playButtonSharedBoundsModifier(),
      )
      if (loading) {
        CircularProgressIndicator(
          modifier = Modifier.size(88.dp),
          strokeWidth = 3.dp,
        )
      }
    }
    Spacer(modifier = Modifier.size(16.dp))
    SkipButton(forward = true, onClick = onFastForwardClick)
  }
}
