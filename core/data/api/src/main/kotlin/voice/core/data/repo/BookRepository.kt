package voice.core.data.repo

import kotlinx.coroutines.flow.Flow
import voice.core.data.Book
import voice.core.data.BookContent
import voice.core.data.BookId
import voice.core.data.ChapterId
import voice.core.data.PlaybackPosition

public interface BookRepository {

  public fun flow(): Flow<List<Book>>

  public suspend fun all(): List<Book>

  public fun flow(id: BookId): Flow<Book?>

  public suspend fun get(id: BookId): Book?

  public suspend fun updateBook(
    id: BookId,
    update: (BookContent) -> BookContent,
  )

  /**
   * The live playback position of [id], or null while none was published.
   *
   * Updated several times per second while playing without re-emitting [flow]
   * or [flow] of a single book: observers that only need the position (the
   * player screen, the shelf card of the running book) read this instead of
   * rebuilding a [Book] per update.
   */
  public fun positionFlow(id: BookId): Flow<PlaybackPosition?>

  /**
   * Stores the playback position of [id].
   *
   * Without [persist] the position is only published through [positionFlow]:
   * the stored content stays untouched, so no [Book] is rebuilt and no book
   * flow re-emits for an update that arrives several times per second. With
   * it the content (and the database) catch up as well.
   */
  public suspend fun updatePlaybackPosition(
    id: BookId,
    currentChapter: ChapterId,
    positionInChapter: Long,
    persist: Boolean,
  )

  /** Refreshes repository caches after remote ids were rewritten in Room. */
  public suspend fun invalidateCaches()
}
