package voice.core.online

import dev.zacsweers.metro.Qualifier
import kotlinx.serialization.Serializable

/**
 * User configurable preload behavior for online books. Online sources are
 * frequently rate limited, so every source api call the player spends ahead
 * of the chapter the user actually listens to is configurable here:
 *
 * - [enabled]: master switch; off means a chapter is resolved only when it plays.
 * - [triggerSeconds]: a chapter starts preloading the next ones only after it
 *   has been playing this long - and only when the chapter itself is at least
 *   this long, so a preload chain can never be cut short by the chapter ending.
 * - [chapterCount]: how many chapters after the current one may hold resolved
 *   stream urls at most; fewer are topped up, more are never fetched.
 * - [intervalSeconds]: pause between two chapter preloads of the same chain.
 *
 * Local and WebDav books never see these settings: their data costs no source
 * api calls.
 */
@Serializable
public data class OnlinePreloadSettings(
  val enabled: Boolean = true,
  val triggerSeconds: Int = 30,
  val chapterCount: Int = 3,
  val intervalSeconds: Int = 5,
) {
  public companion object {
    public val Default: OnlinePreloadSettings = OnlinePreloadSettings()

    public const val MIN_TRIGGER_SECONDS: Int = 5
    public const val MAX_TRIGGER_SECONDS: Int = 300
    public const val MIN_CHAPTER_COUNT: Int = 1
    public const val MAX_CHAPTER_COUNT: Int = 10
    public const val MIN_INTERVAL_SECONDS: Int = 1
    public const val MAX_INTERVAL_SECONDS: Int = 600

    public fun coerce(settings: OnlinePreloadSettings): OnlinePreloadSettings = settings.copy(
      triggerSeconds = settings.triggerSeconds.coerceIn(MIN_TRIGGER_SECONDS, MAX_TRIGGER_SECONDS),
      chapterCount = settings.chapterCount.coerceIn(MIN_CHAPTER_COUNT, MAX_CHAPTER_COUNT),
      intervalSeconds = settings.intervalSeconds.coerceIn(MIN_INTERVAL_SECONDS, MAX_INTERVAL_SECONDS),
    )
  }
}

/** DataStore holding the [OnlinePreloadSettings]. */
@Qualifier
public annotation class OnlinePreloadSettingsStore
