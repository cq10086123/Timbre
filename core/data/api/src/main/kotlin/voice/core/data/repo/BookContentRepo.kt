package voice.core.data.repo

import kotlinx.coroutines.flow.Flow
import voice.core.data.BookContent
import voice.core.data.BookId

public interface BookContentRepo {
  public fun flow(): Flow<List<BookContent>>

  public suspend fun all(): List<BookContent>
  public fun flow(id: BookId): Flow<BookContent?>

  public suspend fun get(id: BookId): BookContent?

  public suspend fun setAllInactiveExcept(ids: List<BookId>)

  /**
   * Stores [content].
   *
   * With [persist] set to false the content is only published to the observers
   * in memory. Frequent updates like the playback position would otherwise
   * rewrite the whole (for big books very long) chapter list to the database
   * multiple times per second.
   */
  public suspend fun put(
    content: BookContent,
    persist: Boolean = true,
  )
}

public suspend inline fun BookContentRepo.getOrPut(
  id: BookId,
  defaultValue: () -> BookContent,
): BookContent {
  return get(id) ?: defaultValue().also { put(it) }
}
