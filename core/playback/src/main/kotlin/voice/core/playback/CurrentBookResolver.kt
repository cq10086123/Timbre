package voice.core.playback

import androidx.datastore.core.DataStore
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.first
import voice.core.data.Book
import voice.core.data.BookId
import voice.core.data.repo.BookRepository
import voice.core.data.store.CurrentBookStore
import voice.core.featureflag.ExperimentalPlaybackPersistenceQualifier
import voice.core.featureflag.FeatureFlag
import voice.core.online.OnlinePlaybackCatalog

@SingleIn(AppScope::class)
@Inject
class CurrentBookResolver(
  private val bookRepository: BookRepository,
  private val playerController: PlayerController,
  private val onlinePlaybackCatalog: OnlinePlaybackCatalog,
  @CurrentBookStore
  private val currentBookStore: DataStore<BookId?>,
  @ExperimentalPlaybackPersistenceQualifier
  private val experimentalPlaybackPersistenceFeatureFlag: FeatureFlag<Boolean>,
) {

  suspend fun currentBook(): Book? {
    val bookId = currentBookStore.data.first() ?: return null
    return book(bookId)
  }

  suspend fun book(bookId: BookId): Book? {
    val persisted = bookRepository.get(bookId)
    // books of the online source live in their own store: the room lookup
    // misses, the online catalog synthesizes the book instead
    val book = persisted ?: onlinePlaybackCatalog.book(bookId) ?: return null
    // persisted books only overlay the live position when the experimental
    // persistence is on; online books have no persisted position at all,
    // so the live position is their only up to date one
    if (persisted != null && !experimentalPlaybackPersistenceFeatureFlag.get()) {
      return book
    }
    val livePosition = playerController.livePlaybackState(bookId) ?: return book
    return book.update {
      it.copy(
        currentChapter = livePosition.chapterId,
        positionInChapter = livePosition.positionMs,
      )
    }
  }
}
