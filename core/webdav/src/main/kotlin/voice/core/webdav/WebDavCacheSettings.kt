package voice.core.webdav

import kotlinx.serialization.Serializable

@Serializable
public data class WebDavCacheSettings(
  /** Maximum cache size in bytes. 0 disables caching entirely. */
  val maxBytes: Long = DEFAULT_MAX_BYTES,
  /** How many upcoming chapters to prefetch. */
  val prefetchMode: PrefetchMode = PrefetchMode.FillBook,
  /** Whether prefetching may run on metered (mobile) networks. Playback caching always runs. */
  val prefetchOnMetered: Boolean = false,
) {
  public enum class PrefetchMode {
    Disabled,
    Chapters5,
    Chapters20,

    /** Prefetch upcoming chapters until the cache limit is reached. */
    FillBook,
  }

  public companion object {
    public const val DEFAULT_MAX_BYTES: Long = 1024L * 1024L * 1024L
    public val Disabled: WebDavCacheSettings =
      WebDavCacheSettings(maxBytes = 0, prefetchMode = PrefetchMode.Disabled)
  }
}
