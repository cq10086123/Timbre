package voice.core.data.repo

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import voice.core.data.BookContent
import voice.core.data.BookId
import voice.core.data.ChapterId
import voice.core.data.PlaybackPosition
import voice.core.data.repo.internals.dao.BookContentDao
import java.util.concurrent.ConcurrentHashMap

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
public class BookContentRepoImpl(private val dao: BookContentDao) : BookContentRepo {

  private val cacheMutex = Mutex()
  private var cacheFilled = false
  private val cache = MutableStateFlow<List<BookContent>?>(null)

  /**
   * Positions published by [publishPosition], one flow per book. A single
   * StateFlow of a map would copy the whole library on every update several
   * times per second; per-book flows keep a position update O(1).
   */
  private val positions = ConcurrentHashMap<BookId, MutableStateFlow<PlaybackPosition?>>()

  private suspend fun fillCache() {
    if (cacheFilled) return
    cacheMutex.withLock {
      if (cacheFilled) return@withLock
      cache.value = dao.all()
      cacheFilled = true
    }
  }

  override fun flow(): Flow<List<BookContent>> {
    return cache.onStart { fillCache() }.filterNotNull()
  }

  override suspend fun all(): List<BookContent> {
    fillCache()
    return cache.value!!
  }

  override fun flow(id: BookId): Flow<BookContent?> {
    return cache.onStart { fillCache() }
      .filterNotNull()
      .map { contents -> contents.find { it.id == id } }
      .distinctUntilChanged()
  }

  override suspend fun get(id: BookId): BookContent? {
    fillCache()
    return cache.value!!.find { it.id == id }
  }

  override suspend fun setAllInactiveExcept(ids: List<BookId>) {
    fillCache()

    val activeIds = ids.toHashSet()
    val changed = cache.value!!
      .filter { it.isActive != (it.id in activeIds) }
      .map { it.copy(isActive = it.id in activeIds) }
    if (changed.isEmpty()) return

    dao.insertAll(changed)
    cache.update { contents ->
      contents!!.map { existing ->
        changed.firstOrNull { it.id == existing.id } ?: existing
      }
    }
  }

  override suspend fun put(
    content: BookContent,
    persist: Boolean,
  ) {
    fillCache()
    if (persist) {
      dao.insert(content)
    }
    cache.update { contents ->
      val newContents = contents!!.toMutableList()
      newContents.removeAll { it.id == content.id }
      newContents.add(content)
      newContents
    }
  }

  override fun positionFlow(id: BookId): Flow<PlaybackPosition?> {
    return positionState(id)
  }

  override suspend fun publishPosition(
    id: BookId,
    chapterId: ChapterId,
    positionInChapter: Long,
  ) {
    // MutableStateFlow drops equal values, so an unchanged position does not
    // wake up the UI either
    positionState(id).value = PlaybackPosition(
      chapterId = chapterId,
      positionInChapter = positionInChapter,
    )
  }

  private fun positionState(id: BookId): MutableStateFlow<PlaybackPosition?> {
    return positions.computeIfAbsent(id) { MutableStateFlow(null) }
  }

  override suspend fun refreshFromDatabase() {
    cacheMutex.withLock {
      cache.value = dao.all()
      cacheFilled = true
    }
  }
}
