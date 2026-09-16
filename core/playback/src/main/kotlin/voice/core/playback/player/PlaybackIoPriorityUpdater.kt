package voice.core.playback.player

import androidx.media3.common.Player
import dev.zacsweers.metro.Inject
import voice.core.common.PlaybackIoGate

@Inject
class PlaybackIoPriorityUpdater(private val playbackIoGate: PlaybackIoGate) : Player.Listener {

  fun attachTo(player: Player) {
    player.addListener(this)
  }

  override fun onPlaybackStateChanged(playbackState: Int) {
    // while the player opens and buffers a chapter, it competes with the
    // scanner for the same storage through the documents provider. The import
    // stands down for that window so the sound starts first.
    playbackIoGate.setAwaitingPlayback(playbackState == Player.STATE_BUFFERING)
  }
}
