package voice.core.common

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update

/**
 * Coordinates io heavy background work (the import) with the player.
 *
 * Opening and buffering a chapter goes through the same documents provider and
 * the same storage as the import analysis. When playback is waiting for its
 * audio, the import stands down so the player gets the storage to itself and
 * the sound starts as fast as if nothing else were running.
 */
@SingleIn(AppScope::class)
@Inject
public class PlaybackIoGate {

  private val awaitingPlayback = MutableStateFlow(false)

  /**
   * Runs [block] only while playback is not loading. The check happens before
   * every import step, so an ongoing import pauses for the short window the
   * player needs to open a chapter and resumes right after the sound is up.
   */
  public suspend fun <T> whilePlaybackLoads(block: suspend () -> T): T {
    awaitingPlayback.first { !it }
    return block()
  }

  public fun setAwaitingPlayback(awaiting: Boolean) {
    awaitingPlayback.update { awaiting }
  }
}
