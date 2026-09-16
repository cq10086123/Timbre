package voice.core.data.repo

import kotlinx.coroutines.flow.Flow
import voice.core.data.Book
import voice.core.data.BookContent
import voice.core.data.BookId
import voice.core.data.ChapterId

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
   * Stores the playback position of [id].
   *
   * With [persist] set to false the position is only published in memory. The
   * position is updated several times per second while playing, and writing it
   * to the database every time rewrites the whole chapter list of the book.
   */
  public suspend fun updatePlaybackPosition(
    id: BookId,
    currentChapter: ChapterId,
    positionInChapter: Long,
    persist: Boolean,
  )
}
