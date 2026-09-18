package voice.core.data.repo

import voice.core.data.Chapter
import voice.core.data.ChapterId

public interface ChapterRepo {

  public suspend fun get(id: ChapterId): Chapter?

  public suspend fun put(chapter: Chapter)

  public suspend fun putAll(chapters: Collection<Chapter>)

  /**
   * Loads the given chapters into the in-memory cache in bulk so that
   * subsequent [get] calls don't trigger one database query per chapter.
   */
  public suspend fun prefetch(ids: Collection<ChapterId>)

  /** Drops cached chapter ids after a database-wide id rewrite. */
  public suspend fun invalidateCache()
}
