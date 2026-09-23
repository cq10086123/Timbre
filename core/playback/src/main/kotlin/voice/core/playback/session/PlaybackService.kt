package voice.core.playback.session

import android.content.Intent
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import voice.core.common.rootGraphAs
import voice.core.logging.api.Logger
import voice.core.playback.di.PlaybackGraph
import voice.core.playback.player.VoicePlayer
import voice.core.playback.playstate.PositionUpdater
import voice.core.playback.prefetch.PrefetchScheduler

class PlaybackService : MediaLibraryService() {

  @Inject
  lateinit var session: MediaLibrarySession

  @Inject
  lateinit var scope: CoroutineScope

  @Inject
  lateinit var player: VoicePlayer

  @Inject
  lateinit var positionUpdater: PositionUpdater

  @Inject
  lateinit var voiceNotificationProvider: VoiceMediaNotificationProvider

  @Inject
  lateinit var prefetchScheduler: PrefetchScheduler

  override fun onCreate() {
    super.onCreate()
    rootGraphAs<PlaybackGraph.Provider>()
      .playbackGraphFactory
      .create(this)
      .inject(this)
    setMediaNotificationProvider(voiceNotificationProvider)
    prefetchScheduler.start()
  }

  private fun release() {
    // Must finish the flush before the player is torn down. NonCancellable keeps
    // a cancelled PlaybackScope from aborting the write; failures are logged so
    // a stuck Room write cannot skip the rest of the teardown.
    runBlocking {
      withContext(NonCancellable) {
        runCatching { positionUpdater.flushPositionNow() }
          .onFailure { Logger.w(it, "Could not flush the position on release") }
      }
    }
    positionUpdater.release()
    player.release()
    session.release()
    scope.cancel()
  }

  override fun onDestroy() {
    super.onDestroy()
    release()
  }

  /**
   * Best-effort position save when the app is swiped away: onDestroy is not
   * guaranteed on a kill, and the periodic updater may hold minutes of
   * unpersisted progress (notably with experimental playback persistence).
   */
  override fun onTaskRemoved(rootIntent: Intent?) {
    super.onTaskRemoved(rootIntent)
    // UNDISPATCHED + NonCancellable: the service is destroyed right after a
    // swipe-away (cancelling the scope), and the flush would otherwise never
    // run. The save is a single fast DataStore write on the main thread.
    scope.launch(start = CoroutineStart.UNDISPATCHED) {
      withContext(NonCancellable) {
        runCatching { positionUpdater.flushPositionNow() }
          .onFailure { Logger.w(it, "Could not flush the position on task removal") }
      }
    }
  }

  override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
    return session.takeUnless { session ->
      session.invokeIsReleased
    }.also {
      if (it == null) {
        Logger.w("onGetSession returns null because the session is already released")
      }
    }
  }
}

private val MediaSession.invokeIsReleased: Boolean
  get() = try {
    // temporarily checked to debug
    // https://github.com/androidx/media/issues/422
    MediaSession::class.java.getDeclaredMethod("isReleased")
      .apply { isAccessible = true }
      .invoke(this) as Boolean
  } catch (e: Exception) {
    Logger.w(e, "Couldn't check if it's released")
    false
  }
