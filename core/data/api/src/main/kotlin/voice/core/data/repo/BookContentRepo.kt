package voice.core.data.repo

import kotlinx.coroutines.flow.Flow
import voice.core.data.BookContent
import voice.core.data.BookId
import voice.core.data.ChapterId
import voice.core.data.PlaybackPosition

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

  /**
   * The position last published for [id], or null while none was published in
   * this process.
   *
   * Positions arrive several times per second while playing. They are kept
   * apart from [flow]: rebuilding the content for every update re-emitted the
   * whole book list and made the shelf regroup and the player re-assemble the
   * running book at that rate.
   */
  public fun positionFlow(id: BookId): Flow<PlaybackPosition?>

  /**
   * Publishes a playback position without touching the stored content, so
   * [flow] keeps its last value. The persisted content catches up on the next
   * [put] - [voice.core.data.repo.BookRepository.updatePlaybackPosition] does
   * that every few seconds, on a chapter change and on pause.
   */
  public suspend fun publishPosition(
    id: BookId,
    chapterId: ChapterId,
    positionInChapter: Long,
  )

  /** Refreshes the in-memory view after an operation that rewrote database ids. */
  public suspend fun refreshFromDatabase()
}

public suspend inline fun BookContentRepo.getOrPut(
  id: BookId,
  defaultValue: () -> BookContent,
): BookContent {
  return get(id) ?: defaultValue().also { put(it) }
}
