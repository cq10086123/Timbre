package voice.core.data.repo

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.SingleIn
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import voice.core.data.Chapter
import voice.core.data.ChapterId
import voice.core.data.repo.internals.dao.ChapterDao
import voice.core.data.runForMaxSqlVariableNumber

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
public class ChapterRepoImpl(private val dao: ChapterDao) : ChapterRepo {

  private val dbMutex = Mutex()

  // Reading a cached chapter must not lock: assembling a book resolves every
  // chapter, and the scanner holds the lock for whole database transactions.
  // With a lock on every read, playing a big book while it is scanned turns
  // into thousands of waits behind database writes.
  private val cache = ConcurrentHashMap<ChapterId, Optional<Chapter>>()

  override suspend fun get(id: ChapterId): Chapter? {
    // this does not use getOrPut because a `null` value should also be cached
    cache[id]?.let { return it.orElse(null) }
    return dbMutex.withLock {
      cache[id]?.let { return it.orElse(null) }
      val chapter = dao.chapter(id)
      cache[id] = Optional.ofNullable(chapter)
      chapter
    }
  }

  override suspend fun prefetch(ids: Collection<ChapterId>) {
    if (ids.isEmpty()) return
    // containsKey, not `in`: `in` on a ConcurrentHashMap calls the legacy
    // contains method, which checks the values instead of the keys
    if (ids.all { cache.containsKey(it) }) return
    dbMutex.withLock {
      val missing = ids.filter { !cache.containsKey(it) }
        .distinct()
      missing
        .runForMaxSqlVariableNumber {
          dao.chapters(it)
        }
        .forEach { cache[it.id] = Optional.of(it) }
      // remember ids that don't exist as null so we don't query them again
      missing.forEach { cache.putIfAbsent(it, Optional.empty()) }
    }
  }

  override suspend fun put(chapter: Chapter) {
    dao.insert(chapter)
    cache[chapter.id] = Optional.of(chapter)
  }

  override suspend fun putAll(chapters: Collection<Chapter>) {
    if (chapters.isEmpty()) return
    dao.insertAll(chapters.toList())
    chapters.forEach { cache[it.id] = Optional.of(it) }
  }
}
