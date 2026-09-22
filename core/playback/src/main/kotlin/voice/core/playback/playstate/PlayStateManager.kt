package voice.core.playback.playstate

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

@SingleIn(AppScope::class)
@Inject
class PlayStateManager {

  val playStateFlow: StateFlow<PlayState>
    field = MutableStateFlow(PlayState.Paused)

  /**
   * True while the player waits for audio data. The playback state handed to
   * the ui maps buffering to ready (see VoicePlayer), so without this a source
   * that stalls or hangs cannot be told apart from one that plays.
   */
  val bufferingFlow: StateFlow<Boolean>
    field = MutableStateFlow(false)

  var playState: PlayState
    set(value) {
      playStateFlow.value = value
    }
    get() = playStateFlow.value

  var buffering: Boolean
    set(value) {
      bufferingFlow.value = value
    }
    get() = bufferingFlow.value

  enum class PlayState {
    Playing,
    Paused,
  }
}
