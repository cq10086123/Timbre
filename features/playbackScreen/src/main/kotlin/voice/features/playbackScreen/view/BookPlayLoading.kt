package voice.features.playbackScreen.view

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * Shown while a book is still being assembled - an online book is fetched from
 * its source (and its chapters may still have to be requested) before the
 * player screen has anything to show. An empty screen looks like broken
 * playback, and it would swallow the errors of that fetch.
 */
@Composable
internal fun BookPlayLoading(snackbarHostState: SnackbarHostState) {
  Scaffold(
    snackbarHost = {
      SnackbarHost(hostState = snackbarHostState)
    },
  ) { contentPadding ->
    Box(
      modifier = Modifier
        .fillMaxSize()
        .padding(contentPadding),
      contentAlignment = Alignment.Center,
    ) {
      CircularProgressIndicator()
    }
  }
}
