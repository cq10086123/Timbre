package voice.features.playbackScreen

import android.content.Context
import android.content.res.Configuration.ORIENTATION_LANDSCAPE
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.retain.retain
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.navigation3.runtime.NavEntry
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides
import voice.core.common.rootGraphAs
import voice.core.data.BookId
import voice.core.online.OnlinePlaybackErrorKind
import voice.features.playbackScreen.view.BookPlayLoading
import voice.features.playbackScreen.view.BookPlayView
import voice.features.sleepTimer.SleepTimerDialog
import voice.navigation.Destination
import voice.navigation.NavEntryProvider
import voice.core.strings.R as StringsR

@Composable
fun BookPlayScreen(bookId: BookId) {
  val viewModel = retain(bookId.value) {
    rootGraphAs<BookPlayGraph>()
      .bookPlayViewModelFactory
      .create(bookId)
  }
  val snackbarHostState = remember { SnackbarHostState() }
  val context = androidx.compose.ui.platform.LocalContext.current
  val dialogState = viewModel.dialogState.value
  val viewState = viewModel.viewState()
  val bookmarkAddedMessage = stringResource(StringsR.string.bookmark_added_snackbar)
  val batteryOptimizationMessage = stringResource(StringsR.string.playback_battery_optimization_rationale)
  val batteryOptimizationAction = stringResource(StringsR.string.playback_battery_optimization_action)
  val onlineNetworkErrorMessage = stringResource(
    if (hasNetwork(context)) {
      StringsR.string.playback_online_error_network
    } else {
      StringsR.string.playback_online_error_offline
    },
  )
  val onlineAuthErrorMessage = stringResource(StringsR.string.playback_online_error_auth)
  val onlineContentErrorMessage = stringResource(StringsR.string.playback_online_error_content)
  LaunchedEffect(viewModel) {
    viewModel.viewEffects.collect { viewEffect ->
      when (viewEffect) {
        BookPlayViewEffect.BookmarkAdded -> {
          snackbarHostState.showSnackbar(message = bookmarkAddedMessage)
        }
        BookPlayViewEffect.RequestIgnoreBatteryOptimization -> {
          val result = snackbarHostState.showSnackbar(
            message = batteryOptimizationMessage,
            duration = SnackbarDuration.Long,
            actionLabel = batteryOptimizationAction,
          )
          if (result == SnackbarResult.ActionPerformed) {
            viewModel.onBatteryOptimizationRequested()
          }
        }
        is BookPlayViewEffect.OnlineSourceError -> {
          val message = when (viewEffect.kind) {
            OnlinePlaybackErrorKind.NETWORK -> onlineNetworkErrorMessage
            OnlinePlaybackErrorKind.AUTH -> onlineAuthErrorMessage
            OnlinePlaybackErrorKind.CONTENT -> onlineContentErrorMessage
          }
          snackbarHostState.showSnackbar(message = message)
        }
      }
    }
  }
  if (viewState == null) {
    // the book is not there yet: without this the screen stays empty while it
    // is assembled, and the errors of that fetch have no host to show up in
    BookPlayLoading(snackbarHostState = snackbarHostState)
    return
  }
  BookPlayView(
    viewState,
    bookId = bookId,
    onPlayClick = viewModel::playPause,
    onFastForwardClick = viewModel::fastForward,
    onRewindClick = viewModel::rewind,
    onSeek = viewModel::seekTo,
    onBookmarkClick = viewModel::onBookmarkClick,
    onBookmarkLongClick = viewModel::onBookmarkLongClick,
    onSkipSilenceClick = viewModel::toggleSkipSilence,
    onSleepTimerClick = viewModel::toggleSleepTimer,
    onVolumeBoostClick = viewModel::onVolumeGainIconClick,
    onSkipIntroOutroClick = viewModel::onSkipIntroOutroIconClick,
    onSpeedChangeClick = viewModel::onPlaybackSpeedIconClick,
    onCloseClick = viewModel::onCloseClick,
    onSkipToNext = viewModel::next,
    onSkipToPrevious = viewModel::previous,
    onCurrentChapterClick = viewModel::onCurrentChapterClick,
    useLandscapeLayout = LocalConfiguration.current.orientation == ORIENTATION_LANDSCAPE,
    snackbarHostState = snackbarHostState,
  )
  if (dialogState != null) {
    when (dialogState) {
      is BookPlayDialogViewState.SpeedDialog -> {
        SpeedDialog(dialogState, viewModel)
      }
      is BookPlayDialogViewState.VolumeGainDialog -> {
        VolumeGainDialog(dialogState, viewModel)
      }
      is BookPlayDialogViewState.SkipDialog -> {
        SkipDialog(dialogState, viewModel)
      }
      is BookPlayDialogViewState.SelectChapterDialog -> {
        SelectChapterDialog(dialogState, viewModel)
      }
      is BookPlayDialogViewState.SleepTimer -> {
        SleepTimerDialog(
          viewState = dialogState.viewState,
          onDismiss = viewModel::dismissDialog,
          onIncrementSleepTime = viewModel::incrementSleepTime,
          onDecrementSleepTime = viewModel::decrementSleepTime,
          onAcceptSleepTime = viewModel::onAcceptSleepTime,
          onAcceptSleepAtEndOfChapter = viewModel::onAcceptSleepAtEndOfChapter,
        )
      }
    }
  }
}

private fun hasNetwork(context: Context): Boolean {
  val manager = context.getSystemService(ConnectivityManager::class.java) ?: return true
  val network = manager.activeNetwork ?: return false
  val capabilities = manager.getNetworkCapabilities(network) ?: return false
  return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}

@ContributesTo(AppScope::class)
interface BookPlayGraph {
  val bookPlayViewModelFactory: BookPlayViewModel.Factory
}

@ContributesTo(AppScope::class)
interface BookPlayProvider {

  @Provides
  @IntoSet
  fun bookPlayNavEntryProvider(): NavEntryProvider<*> = NavEntryProvider<Destination.Playback> { key ->
    NavEntry(key) {
      BookPlayScreen(bookId = key.bookId)
    }
  }
}
