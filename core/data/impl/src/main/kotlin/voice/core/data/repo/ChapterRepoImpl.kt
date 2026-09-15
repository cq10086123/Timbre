package voice.core.data.repo

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import voice.core.data.Chapter
import voice.core.data.ChapterId
import voice.core.data.repo.internals.dao.ChapterDao
import voice.core.data.runForMaxSqlVariableNumber

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
public class ChapterRepoImpl(private val dao: ChapterDao) : ChapterRepo {

  private val mutex = Mutex()
  private val cache = mutableMapOf<ChapterId, Chapter?>()

  override suspend fun get(id: ChapterId): Chapter? {
    // this does not use getOrPut because a `null` value should also be cached
    mutex.withLock {
      if (!cache.containsKey(id)) {
        cache[id] = dao.chapter(id)
      }
      return cache[id]
    }
  }

  override suspend fun prefetch(ids: Collection<ChapterId>) {
    if (ids.isEmpty()) return
    mutex.withLock {
      val missing = ids.filter { it !in cache }
        .distinct()
      missing
        .runForMaxSqlVariableNumber {
          dao.chapters(it)
        }
        .forEach { cache[it.id] = it }
      // remember ids that don't exist as null so we don't query them again
      missing.forEach { cache.putIfAbsent(it, null) }
    }
  }

  override suspend fun put(chapter: Chapter) {
    dao.insert(chapter)
    mutex.withLock {
      cache[chapter.id] = chapter
    }
  }

  override suspend fun putAll(chapters: Collection<Chapter>) {
    if (chapters.isEmpty()) return
    dao.insertAll(chapters.toList())
    mutex.withLock {
      chapters.forEach { cache[it.id] = it }
    }
  }
}
