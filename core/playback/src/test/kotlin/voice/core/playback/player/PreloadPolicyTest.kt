package voice.core.playback.player

import voice.core.online.OnlinePreloadSettings
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PreloadPolicyTest {

  private val settings = OnlinePreloadSettings(
    enabled = true,
    triggerSeconds = 30,
    chapterCount = 3,
    intervalSeconds = 5,
  )

  private fun input(
    playing: Boolean = true,
    playedMs: Long = 30_000,
    chapterDurationMs: Long = 120_000,
    preloadedAheadCount: Int = 0,
    chainRunning: Boolean = false,
    preset: OnlinePreloadSettings = settings,
  ) = PreloadPolicy.Input(
    settings = preset,
    playing = playing,
    playedMs = playedMs,
    chapterDurationMs = chapterDurationMs,
    preloadedAheadCount = preloadedAheadCount,
    chainRunning = chainRunning,
  )

  @Test
  fun `starts after the trigger time while playing`() {
    assertTrue(PreloadPolicy.shouldStartNextPreload(input()))
  }

  @Test
  fun `does not start before the trigger time`() {
    assertFalse(PreloadPolicy.shouldStartNextPreload(input(playedMs = 29_999)))
  }

  @Test
  fun `starts exactly at the trigger time`() {
    assertTrue(PreloadPolicy.shouldStartNextPreload(input(playedMs = 30_000)))
  }

  @Test
  fun `does not start while paused`() {
    assertFalse(PreloadPolicy.shouldStartNextPreload(input(playing = false)))
  }

  @Test
  fun `does not start while a chain is already running`() {
    assertFalse(PreloadPolicy.shouldStartNextPreload(input(chainRunning = true)))
  }

  @Test
  fun `does not start when the cap is reached`() {
    assertFalse(PreloadPolicy.shouldStartNextPreload(input(preloadedAheadCount = 3)))
  }

  @Test
  fun `starts below the cap`() {
    assertTrue(PreloadPolicy.shouldStartNextPreload(input(preloadedAheadCount = 2)))
  }

  @Test
  fun `a chapter shorter than the trigger never preloads`() {
    assertFalse(PreloadPolicy.shouldStartNextPreload(input(chapterDurationMs = 29_999)))
  }

  @Test
  fun `a chapter exactly as long as the trigger preloads`() {
    assertTrue(PreloadPolicy.shouldStartNextPreload(input(chapterDurationMs = 30_000)))
  }

  @Test
  fun `an unknown chapter duration never preloads`() {
    assertFalse(PreloadPolicy.shouldStartNextPreload(input(chapterDurationMs = 0)))
    assertFalse(PreloadPolicy.shouldStartNextPreload(input(chapterDurationMs = -1)))
  }

  @Test
  fun `disabled settings never preload`() {
    assertFalse(
      PreloadPolicy.shouldStartNextPreload(
        input(preset = settings.copy(enabled = false)),
      ),
    )
  }

  @Test
  fun `the cap follows the configured chapter count`() {
    val oneChapter = settings.copy(chapterCount = 1)
    assertFalse(PreloadPolicy.shouldStartNextPreload(input(preloadedAheadCount = 1, preset = oneChapter)))
    assertTrue(PreloadPolicy.shouldStartNextPreload(input(preloadedAheadCount = 0, preset = oneChapter)))
  }
}
