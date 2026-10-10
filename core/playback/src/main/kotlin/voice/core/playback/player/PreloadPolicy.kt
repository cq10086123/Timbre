package voice.core.playback.player

import voice.core.online.OnlinePreloadSettings

/**
 * Pure decision core of the online preload state machine, kept free of player
 * and coroutine dependencies so the rules are unit testable:
 *
 * - preload only while the chapter is actually playing,
 * - only after it played [OnlinePreloadSettings.triggerSeconds],
 * - a chapter shorter than the trigger never preloads (its chain could not
 *   finish before the chapter ends),
 * - never beyond [OnlinePreloadSettings.chapterCount] resolved chapters ahead.
 */
internal object PreloadPolicy {

  /** All durations in milliseconds; [chapterDurationMs] <= 0 means unknown. */
  public data class Input(
    val settings: OnlinePreloadSettings,
    val playing: Boolean,
    val playedMs: Long,
    val chapterDurationMs: Long,
    val preloadedAheadCount: Int,
    val chainRunning: Boolean,
  )

  public fun shouldStartNextPreload(input: Input): Boolean {
    val settings = input.settings
    if (!settings.enabled) return false
    if (!input.playing) return false
    if (input.chainRunning) return false
    if (input.preloadedAheadCount >= settings.chapterCount) return false
    val triggerMs = settings.triggerSeconds * 1_000L
    // an unknown duration (<= 0) counts as "too short to preload": starting a
    // chain for it could waste the source budget right before a chapter switch
    if (input.chapterDurationMs < triggerMs) return false
    if (input.playedMs < triggerMs) return false
    return true
  }
}
