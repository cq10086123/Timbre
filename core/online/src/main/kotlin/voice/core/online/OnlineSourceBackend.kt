package voice.core.online

/**
 * One backend behind [OnlineSourceRouter]: the built-in server source, or a
 * jdr plugin package. The router picks by source name prefix.
 */
public interface OnlineSourceBackend {

  /** The sources this backend can serve, for the search source chips. */
  public suspend fun sources(): List<OnlineSourceInfo>

  public suspend fun search(source: String, keyword: String): List<OnlineSearchResult>

  public suspend fun chapters(source: String, bookId: String): List<OnlineChapter>

  /**
   * Resolves the direct audio of one chapter. [chapterExtra] round-trips the
   * opaque payload the source attached to the chapter.
   */
  public suspend fun resolveAudio(
    source: String,
    bookId: String,
    chapterId: String,
    chapterExtra: String,
  ): OnlineAudio
}
