package voice.core.data.repo

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import voice.core.data.Book
import voice.core.data.BookContent
import voice.core.data.BookId
import voice.core.data.Chapter
import voice.core.data.ChapterId
import voice.core.logging.api.Logger
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
public class BookRepositoryImpl(
  private val chapterRepo: ChapterRepo,
  private val contentRepo: BookContentRepo,
) : BookRepository {

  private var warmedUp = false
  private val mutex = Mutex()

  /**
   * Assembling a book resolves every chapter and validates the result, which
   * for a book with hundreds of chapters is expensive. Positions are updated
   * several times per second while playing, so the result is cached and reused
   * as long as neither the content nor any of its chapters changed.
   */
  private val bookCache = ConcurrentHashMap<BookId, CachedBook>()

  private suspend fun warmUp() {
    if (warmedUp) return
    mutex.withLock {
      if (warmedUp) return@withLock
      val chapters = contentRepo.all()
        .filter { it.isActive }
        .flatMap { it.chapters }
      chapterRepo.prefetch(chapters)
      warmedUp = true
    }
  }

  /**
   * The books of the whole library. Positions are updated several times per
   * second while playing, which re-assembles the playing book on every
   * update; resolving the chapters only reads the lock-free cache of the
   * chapter repo, so this stays cheap.
   */
  override fun flow(): Flow<List<Book>> {
    return contentRepo.flow()
      .map { contents ->
        contents.filter { it.isActive }
          .mapNotNull { content ->
            content.book()
          }
      }
      .distinctUntilChanged()
  }

  override suspend fun all(): List<Book> {
    return contentRepo.all()
      .filter { it.isActive }
      .mapNotNull { content ->
        content.book()
      }
  }

  override fun flow(id: BookId): Flow<Book?> {
    return contentRepo.flow(id)
      .map { it?.book() }
  }

  override suspend fun get(id: BookId): Book? {
    return contentRepo.get(id)?.book()
  }

  override suspend fun updateBook(
    id: BookId,
    update: (BookContent) -> BookContent,
  ) {
    mutex.withLock {
      val content = contentRepo.get(id) ?: return
      val updated = update(content)
      if (updated != content) {
        contentRepo.put(updated)
      }
    }
  }

  override suspend fun updatePlaybackPosition(
    id: BookId,
    currentChapter: ChapterId,
    positionInChapter: Long,
    persist: Boolean,
  ) {
    mutex.withLock {
      val content = contentRepo.get(id) ?: return
      if (currentChapter !in content.chapters) {
        Logger.w("Ignoring the position of $currentChapter because it is not part of $id")
        return
      }
      val updated = content.copy(
        currentChapter = currentChapter,
        positionInChapter = positionInChapter,
        lastPlayedAt = Instant.now(),
      )
      if (updated != content) {
        contentRepo.put(updated, persist = persist)
      }
    }
  }

  override suspend fun invalidateCaches() {
    mutex.withLock {
      contentRepo.refreshFromDatabase()
      chapterRepo.invalidateCache()
      bookCache.clear()
      warmedUp = false
    }
  }

  private suspend fun BookContent.book(): Book? {
    warmUp()
    // a single bulk query per unknown chapter instead of one select per chapter
    chapterRepo.prefetch(chapters)
    val chapters = this.chapters.map { chapterId ->
      val chapter = chapterRepo.get(chapterId)
      if (chapter == null) {
        Logger.w("Missing chapter with id=$chapterId for $this")
        return null
      }
      chapter
    }
    bookCache[id]
      ?.takeIf { it.isUpToDate(this, chapters) }
      ?.let { return it.book }
    return Book(content = this, chapters = chapters)
      .also { bookCache[id] = CachedBook(content = this, chapters = chapters, book = it) }
  }

  private class CachedBook(
    private val content: BookContent,
    private val chapters: List<Chapter>,
    val book: Book,
  ) {

    fun isUpToDate(
      content: BookContent,
      chapters: List<Chapter>,
    ): Boolean {
      if (this.content !== content) return false
      if (this.chapters.size != chapters.size) return false
      for (index in this.chapters.indices) {
        if (chapters[index] !== this.chapters[index]) return false
      }
      return true
    }
  }
}
