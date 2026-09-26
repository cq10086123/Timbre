package voice.core.source

/**
 * One book source a plugin registered. A jdr package may register several;
 * each is enabled or disabled independently in the source manager.
 */
public interface BookSource {

  /** Unique key of this source inside the app: `packageId:sourceId`. */
  public val id: String

  /** Display name shown in the search chips. */
  public val displayName: String

  /** The jdr package this source came from. */
  public val packageId: String

  public suspend fun search(keyword: String, page: Int): SourcePage

  public suspend fun chapters(bookId: String): List<SourceChapter>

  public suspend fun audio(
    bookId: String,
    chapterId: String,
    chapterExtra: String,
  ): SourceAudio
}
