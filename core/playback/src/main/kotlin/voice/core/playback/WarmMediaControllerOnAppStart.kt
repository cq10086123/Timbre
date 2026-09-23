package voice.core.playback

import android.app.Application
import android.os.Handler
import android.os.Looper
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import voice.core.common.DispatcherProvider
import voice.core.common.MainScope
import voice.core.initializer.AppInitializer
import voice.core.logging.api.Logger

/**
 * Starts the [PlayerController] MediaController connection after the first
 * frame so a later play/prepare does not pay the binder + service cold start
 * on the critical path. Double-posted so Application.onCreate and the first
 * composition stay free of playback work; [PlayerController] itself defers
 * the binder bind until first use so constructing this initializer is cheap.
 */
@ContributesIntoSet(AppScope::class)
@Inject
class WarmMediaControllerOnAppStart(
  private val playerController: PlayerController,
  dispatcherProvider: DispatcherProvider,
) : AppInitializer {

  private val scope: CoroutineScope = MainScope(dispatcherProvider)

  override fun onAppStart(application: Application) {
    val main = Handler(Looper.getMainLooper())
    main.post {
      // one more hop so setContent / first draw can finish first
      main.post {
        scope.launch {
          // never wait forever: a warming attempt that cannot connect (no
          // service yet, tests, a stuck binder) must not keep a coroutine or
          // the app start alive
          val controller = withTimeoutOrNull(WARM_TIMEOUT_MS) {
            playerController.awaitConnect()
          }
          if (controller == null) {
            Logger.w("Warming the media controller timed out")
          }
        }
      }
    }
  }
}

/** Upper bound for the warm up attempt; the real connect happens on demand. */
private const val WARM_TIMEOUT_MS = 10_000L
