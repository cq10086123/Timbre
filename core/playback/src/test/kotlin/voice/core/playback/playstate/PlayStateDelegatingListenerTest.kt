package voice.core.playback.playstate

import androidx.media3.common.Player
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayStateDelegatingListenerTest {

  private val player = mockk<Player>(relaxed = true) {
    every { playbackState } returns Player.STATE_IDLE
  }
  private val playStateManager = PlayStateManager()
  private val listener = PlayStateDelegatingListener(playStateManager)

  @Test
  fun `reports buffering while the player waits for data`() {
    every { player.playbackState } returns Player.STATE_BUFFERING
    every { player.playWhenReady } returns true

    listener.attachTo(player)

    assertTrue(playStateManager.buffering)
    assertEquals(expected = PlayStateManager.PlayState.Playing, actual = playStateManager.playState)
  }

  @Test
  fun `clears buffering once the player has data again`() {
    listener.attachTo(player)
    every { player.playbackState } returns Player.STATE_BUFFERING
    listener.onPlaybackStateChanged(Player.STATE_BUFFERING)
    assertTrue(playStateManager.buffering)

    every { player.playbackState } returns Player.STATE_READY
    every { player.playWhenReady } returns true
    listener.onPlaybackStateChanged(Player.STATE_READY)

    assertFalse(playStateManager.buffering)
    assertEquals(expected = PlayStateManager.PlayState.Playing, actual = playStateManager.playState)
  }
}
