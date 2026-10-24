package voice.core.online

/**
 * A locally executed source backend (imported .jdr extension packages).
 * [OnlineSourceService] dispatches to the first backend whose
 * [handles] returns true for a source name before ever touching the
 * remote download server, so extension sources work without any
 * server configuration and run entirely on the user's device.
 */
public interface ExtensionOnlineSource {

  /** Whether this backend claims the given source name (e.g. `jdr:` prefixed). */
  public fun handles(source: String): Boolean

  /** Whether at least one source of this backend is enabled. */
  public suspend fun hasEnabledSources(): Boolean

  /** Enabled sources as search chips, with their `jdr:` prefixed names. */
  public suspend fun enabledSourceInfos(): List<OnlineSourceInfo>

  public suspend fun search(
    source: String,
    keyword: String,
  ): List<OnlineSearchResult>

  public suspend fun chapters(
    source: String,
    bookId: String,
  ): List<OnlineChapter>

  /**
   * Resolves a playable url for one chapter.
   *
   * [order] is the chapter's position in the album as persisted in
   * [OnlineChapter.order]. Sources whose audio stage only accepts an episode
   * number (instead of the chapter id) would otherwise have to page through
   * their own chapter list to find it, because the extras collected during
   * [chapters] live in an in-memory cache that a cold start loses. Passing it
   * costs nothing for sources that do not read it.
   */
  public suspend fun resolveDirectUrl(
    source: String,
    bookId: String,
    chapterId: String,
    order: Int? = null,
  ): ResolvedStream?
}
