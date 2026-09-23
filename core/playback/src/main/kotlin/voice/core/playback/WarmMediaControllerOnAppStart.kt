package voice.core.playback

import android.app.Application
import android.os.Handler
import android.os.Looper
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
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
          runCatching { playerController.awaitConnect() }
            .onFailure { Logger.w(it, "Warming the media controller failed") }
        }
      }
    }
  }
}
