package voice.core.online

import kotlinx.serialization.Serializable

/**
 * One manually started cache job of an online book, persisted so the job
 * survives a process the system killed mid download: the next app start hands
 * the unfinished jobs back to [OnlineBookCacheManager]. A finished job, a
 * cancelled one, one whose book was deleted and one that keeps failing without
 * progress are dropped from the store, so the store only ever holds real
 * pending work.
 */
@Serializable
public data class OnlineCacheJob(
  /** Canonical book uri (`online://book/<source>/<bookId>`), which is also its [voice.core.data.BookId]. */
  val bookUri: String,
  /** How many chapters starting at the chapter in progress were requested. */
  val count: Int,
  /** Seconds between two episodes of the main catalog (0 = no delay). */
  val delaySeconds: Int = 0,
  /**
   * How many runs of this job already ended without downloading a single
   * chapter. A run that downloaded something resets it, so a slow cache is
   * never given up on, while a source that is gone stops being retried on
   * every app start.
   */
  val attempts: Int = 0,
)

/**
 * Whether the active network is metered (mobile data). Manual caching is the
 * only job that spends the user's data volume on purpose, so it asks for
 * permission before it does; this seam keeps that check testable and lets the
 * production graph resolve it from [android.net.ConnectivityManager].
 */
public fun interface MeteredNetworkChecker {
  public fun isMetered(): Boolean
}
