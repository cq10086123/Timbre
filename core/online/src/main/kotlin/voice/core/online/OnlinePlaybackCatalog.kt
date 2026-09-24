package voice.core.online

import android.os.SystemClock
import androidx.datastore.core.DataStore
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import voice.core.data.Book
import voice.core.data.BookContent
import voice.core.data.BookId
import voice.core.data.Chapter
import voice.core.data.ChapterId
import voice.core.logging.api.Logger
import java.io.IOException
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** Why the playback of an online chapter could not be resolved. */
public enum class OnlinePlaybackErrorKind {
  /** The server could not be reached or a request failed. */
  NETWORK,

  /** The access key was rejected. */
  AUTH,

  /** The source has no audio for this chapter. */
  CONTENT,
}

/** Emitted when an online chapter could not be resolved for playback. */
public data class OnlinePlaybackError(
  /** The book in its canonical `online://book/...` form. */
  val bookUri: String,
  /** The source specific chapter id. */
  val chapterId: String,
  val kind: OnlinePlaybackErrorKind,
  val detail: String?,
)

/**
 * Bridges the online source into the playback pipeline:
 * - synthesizes a [Book] (with `online://play?...` chapter uris) for books on
 *   the shelf, so the regular MediaItem machinery works without Room,
 * - resolves chapter uris to playable urls: direct links for pluggable
 *   sources, downloaded files with on-demand download (keeping a download
 *   ahead of the playing episode) for the main catalog.
 */
@Inject
@SingleIn(AppScope::class)
public class OnlinePlaybackCatalog(
  private val service: OnlineSourceService,
  @OnlineSourceBooksStore private val booksStore: DataStore<List<OnlineBook>>,
) {

  private val stateLock = Any()

  /** Chapter the user tapped in the search dialog, keyed by [OnlineBookRef.key]. */
  private val pendingStarts = mutableMapOf<String, String>()

  /** Last known playback position per book key, so re-assembly resumes. */
  private val positions = mutableMapOf<String, OnlinePosition>()

  /**
   * The position that was last written to the shelf store, keyed by book key.
   * [positions] is the fresher in-memory copy; this one survives process death.
   */
  private val persistedPositions = HashMap<String, OnlinePosition>()

  /** When the position of a book was persisted last, so writes can be throttled. */
  private val lastPositionPersistAt = HashMap<String, Long>()

  /**
   * Owns the throttled position persistence. Positions arrive several times
   * per second on the caller's thread (often main); the store write happens
   * here instead of a blocking bridge.
   */
  private val persistenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  /**
   * Books whose chapters were fetched in the search dialog and whose playback
   * was started without adding them to the shelf: the player can still
   * assemble the book from here, the shelf simply does not show them.
   * Entries are also consulted by [resolveMain] (for the book title) because
   * the shelf lookup misses for books that were never added.
   */
  private val pendingBooks = ConcurrentHashMap<String, OnlineBook>()

  /**
   * Books assembled for playback in this session (shelf or stash). The resolve
   * step runs after assembly on the player thread; without this the title of
   * a search-stashed book would be lost once [book] consumed the stash.
   */
  private val assembledBooks = ConcurrentHashMap<String, OnlineBook>()

  /**
   * Canonical book uris whose stream url is being resolved right now (a resolve
   * may download the chapter first and can take a while). Counted per book: the
   * player re-opens a chapter on every seek, and several books are resolved
   * over a session, so a single flag would light up the loading ring of another
   * book and would go off as soon as the first of two resolves returns.
   */
  private val resolvingCounts = mutableMapOf<String, Int>()
  private val _resolvingBooks = MutableStateFlow<Set<String>>(emptySet())
  public val resolvingBooks: StateFlow<Set<String>> get() = _resolvingBooks

  /**
   * Streaming urls the sources handed out, keyed by the canonical chapter uri.
   * The player opens a chapter again on every seek (and the auto rewind seeks
   * on pause), so without the cache each of those re-runs the resolution - an
   * api round trip per seek, together with a loading ring for a chapter that
   * already plays.
   */
  private val streamUrls = object : LinkedHashMap<String, CachedStreamUrl>(0, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedStreamUrl>): Boolean {
      return size > MAX_CACHED_STREAM_URLS
    }
  }

  /**
   * In-flight resolve jobs keyed by chapter uri. ExoPlayer and the duration
   * probe can open the same chapter concurrently; coalescing them avoids two
   * full download/API round trips on a cold start.
   */
  private val inFlightResolves = ConcurrentHashMap<String, Deferred<String?>>()

  /**
   * Bumped by [invalidateStreamUrl] so a resolve that outlives an invalidate
   * cannot re-publish a rejected url into [streamUrls].
   */
  private val streamUrlEpoch = ConcurrentHashMap<String, Int>()

  /** Bumped whenever a measured duration lands, so the UI can rebuild. */
  private val _durationsVersion = MutableStateFlow(0)
  public val durationsVersion: kotlinx.coroutines.flow.StateFlow<Int> get() = _durationsVersion

  /**
   * Durations measured from the actual stream, keyed by the canonical chapter
   * uri. Sources that do not report durations get corrected here after the
   * first play of a chapter.
   */
  private val measuredDurations = ConcurrentHashMap<String, Long>()

  /** Downloaded albums with a timestamp, so resolver loops do not refetch per poll. */
  private var albumsCache: Pair<Long, List<FilesAlbum>>? = null

  private val _playbackErrors = MutableSharedFlow<OnlinePlaybackError>(extraBufferCapacity = 1)

  /** Playback resolution failures, deduplicated, for the UI to surface. */
  public val playbackErrors: SharedFlow<OnlinePlaybackError> = _playbackErrors

  private var lastErrorKey: String? = null
  private var lastErrorAt = 0L

  /** True when [bookId] addresses a book of the online source. */
  public fun isOnlineBookId(bookId: BookId): Boolean {
    return OnlineUri.parseBookUri(bookId.value) != null
  }

  /** The books on the online shelf. Emits on every position, chapter or settings change. */
  public fun shelfBooks(): Flow<List<OnlineBook>> {
    return booksStore.data
  }

  /** Persists the intro skip of an online book (in milliseconds). */
  public suspend fun setSkipIntro(
    bookId: BookId,
    skipMs: Long,
  ) {
    val bookRef = OnlineUri.parseBookUri(bookId.value) ?: return
    val value = skipMs.coerceAtLeast(0L)
    pendingBooks[bookRef.key]?.let { pendingBooks[bookRef.key] = it.copy(skipIntroMs = value) }
    assembledBooks[bookRef.key]?.let { assembledBooks[bookRef.key] = it.copy(skipIntroMs = value) }
    runCatching {
      booksStore.updateData { books ->
        books.map { book ->
          if (book.key == bookRef.key) book.copy(skipIntroMs = value) else book
        }
      }
    }.onFailure { Logger.w("Failed to persist the online skip intro of ${bookRef.key}: $it") }
  }

  /** Persists the outro skip of an online book (in milliseconds). */
  public suspend fun setSkipOutro(
    bookId: BookId,
    skipMs: Long,
  ) {
    val bookRef = OnlineUri.parseBookUri(bookId.value) ?: return
    val value = skipMs.coerceAtLeast(0L)
    pendingBooks[bookRef.key]?.let { pendingBooks[bookRef.key] = it.copy(skipOutroMs = value) }
    assembledBooks[bookRef.key]?.let { assembledBooks[bookRef.key] = it.copy(skipOutroMs = value) }
    runCatching {
      booksStore.updateData { books ->
        books.map { book ->
          if (book.key == bookRef.key) book.copy(skipOutroMs = value) else book
        }
      }
    }.onFailure { Logger.w("Failed to persist the online skip outro of ${bookRef.key}: $it") }
  }

  /**
   * Re-fetches the chapter list of [bookId] from the source and stores it on
   * the shelf. Durations measured from real streams, the playback position
   * and the skip settings survive the update; a failed or empty fetch keeps
   * the stored chapters untouched so playback continues with them.
   */
  public suspend fun refreshChapters(bookId: BookId): OnlineChapterRefreshResult {
    val bookRef = OnlineUri.parseBookUri(bookId.value)
      ?: return OnlineChapterRefreshResult.Failed(null)
    val shelf = runCatching { service.shelfBook(bookRef.key) }.getOrNull()
      ?: return OnlineChapterRefreshResult.Failed(null)
    val fresh = try {
      service.refreshChapters(bookRef.source, bookRef.bookId)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Logger.w("Online chapter refresh failed for ${bookRef.key}: $e")
      return OnlineChapterRefreshResult.Failed(e.message)
    }
    if (fresh.isEmpty()) {
      return OnlineChapterRefreshResult.Failed(null)
    }
    val oldById = shelf.chapters.associateBy { it.id }
    val merged = fresh.map { chapter ->
      val measured = measuredDurations[OnlineUri.build(bookRef.source, bookRef.bookId, chapter.id)]
      val previous = oldById[chapter.id]
      when {
        measured != null && measured > 0L -> chapter.copy(durationSeconds = (measured / 1_000L).toInt())
        previous != null && chapter.durationSeconds <= 0 && previous.durationSeconds > 0 ->
          chapter.copy(durationSeconds = previous.durationSeconds)
        else -> chapter
      }
    }
    val added = fresh.count { it.id !in oldById }
    val freshIds = fresh.map { it.id }.toSet()
    // the chapter the user was listening to may have vanished from the
    // source: reset the stored position instead of pointing at nothing
    val positionSurvives = shelf.currentChapterId.isBlank() || shelf.currentChapterId in freshIds
    runCatching {
      booksStore.updateData { books ->
        books.map { book ->
          if (book.key != bookRef.key) {
            book
          } else {
            book.copy(
              chapters = merged,
              currentChapterId = if (positionSurvives) shelf.currentChapterId else "",
              positionMs = if (positionSurvives) shelf.positionMs else 0L,
            )
          }
        }
      }
    }.onFailure {
      Logger.w("Failed to persist refreshed online chapters of ${bookRef.key}: $it")
      return OnlineChapterRefreshResult.Failed(it.message)
    }
    // the session copies hold the previous chapter list: drop them so the
    // next assembly reads the refreshed shelf entry
    assembledBooks.remove(bookRef.key)
    pendingBooks.remove(bookRef.key)
    if (!positionSurvives) {
      synchronized(stateLock) {
        positions.remove(bookRef.key)
      }
    }
    return if (added > 0) {
      OnlineChapterRefreshResult.Updated(added = added, total = fresh.size)
    } else {
      OnlineChapterRefreshResult.UpToDate(total = fresh.size)
    }
  }

  /** The user tapped [chapterId]: the next assembly of the book starts there. */
  public fun requestStartAt(
    source: String,
    bookId: String,
    chapterId: String,
  ) {
    synchronized(stateLock) {
      pendingStarts[OnlineBookRef(source, bookId).key] = chapterId
      positions.remove(OnlineBookRef(source, bookId).key)
    }
  }

  /** The last playback position of an online book, so the UI can resume it. */
  public fun updatePosition(
    bookId: BookId,
    chapterId: ChapterId,
    positionMs: Long,
    durationMs: Long = 0L,
  ) {
    val bookRef = OnlineUri.parseBookUri(bookId.value) ?: return
    val chapterRef = OnlineUri.parse(chapterId.value) ?: return
    val position = OnlinePosition(chapterRef.chapterId, positionMs)
    val shouldPersist = synchronized(stateLock) {
      pendingStarts.remove(bookRef.key)
      positions[bookRef.key] = position
      val persisted = persistedPositions[bookRef.key]
      if (persisted == position) {
        false
      } else {
        val due = SystemClock.elapsedRealtime() - (lastPositionPersistAt[bookRef.key] ?: 0L) >= POSITION_PERSIST_INTERVAL_MS
        // a chapter change is persisted immediately, a position change at most
        // once per interval: a killed process then loses a few seconds at most
        due || persisted?.chapterId != position.chapterId
      }
    }
    if (shouldPersist) {
      persistPosition(bookRef, position)
    }
    if (durationMs > 0L) {
      recordMeasuredDuration(bookRef.source, bookRef.bookId, chapterRef.chapterId, durationMs)
    }
  }

  /**
   * Writes [position] into the shelf entry of [bookRef] off the caller thread.
   * Books that only live in the search stash have no shelf entry: the update
   * is a no-op for them (and DataStore skips the disk write when nothing
   * changed).
   */
  private fun persistPosition(
    bookRef: OnlineBookRef,
    position: OnlinePosition,
  ) {
    persistenceScope.launch {
      try {
        booksStore.updateData { books ->
          books.map { book ->
            if (book.key == bookRef.key) {
              book.copy(currentChapterId = position.chapterId, positionMs = position.positionMs)
            } else {
              book
            }
          }
        }
        synchronized(stateLock) {
          persistedPositions[bookRef.key] = position
          lastPositionPersistAt[bookRef.key] = SystemClock.elapsedRealtime()
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Logger.w("Failed to persist the online playback position of ${bookRef.key}: $e")
      }
    }
  }

  /**
   * Keeps a book around for playback without adding it to the shelf.
   */
  public fun stashForPlayback(book: OnlineBook) {
    if (pendingBooks.size >= STASH_CAP) {
      pendingBooks.keys.firstOrNull()?.let { pendingBooks.remove(it) }
    }
    pendingBooks[OnlineBookRef(book.source, book.bookId).key] = book
  }

  /**
   * The online book behind [source]/[bookId]: shelf first, then the session
   * maps (search stash, assembled copy). Used by resolve and prefetch paths
   * that run after assembly and cannot rely on the one-shot stash.
   */
  internal suspend fun lookupOnlineBook(
    source: String,
    bookId: String,
  ): OnlineBook? {
    val key = "$source::$bookId"
    service.shelfBook(key)?.let { return it }
    pendingBooks[key]?.let { return it }
    assembledBooks[key]?.let { return it }
    return null
  }

  /** A measured stream duration in ms, or null when the chapter was never probed. */
  public fun measuredDurationMs(
    source: String,
    bookId: String,
    chapterId: String,
  ): Long? {
    return measuredDurations[OnlineUri.build(source, bookId, chapterId)]
  }

  /**
   * The remote cover url of an online book (shelf, search stash or assembled
   * copy), or null when unknown. The synthesized [Book] carries no cover, so
   * the player UI and notification use this instead.
   */
  public suspend fun onlineCover(bookId: BookId): String? {
    val bookRef = OnlineUri.parseBookUri(bookId.value) ?: return null
    pendingBooks[bookRef.key]?.cover
      ?.takeIf { it.isNotBlank() }?.let { return it }
    assembledBooks[bookRef.key]?.cover
      ?.takeIf { it.isNotBlank() }?.let { return it }
    return service.shelfBook(bookRef.key)?.cover?.takeIf { it.isNotBlank() }
  }

  /** Synthesizes the [Book] of an online book, or null when [bookId] is not one. */
  public suspend fun book(bookId: BookId): Book? {
    val assembled = assembleOnline(bookId) ?: return null
    val dataChapters = assembled.chapters.map { chapter ->
      val uri = OnlineUri.build(assembled.bookRef.source, assembled.bookRef.bookId, chapter.id)
      Chapter(
        id = ChapterId(uri),
        name = chapter.title,
        // prefer a duration measured from the actual stream; sources that do
        // not report one fall back to a placeholder so the player never clips
        // the chapter away - it gets corrected after the first play
        duration = measuredDurations[uri]
          ?: (chapter.durationSeconds.takeIf { it > 0 }?.let { it * 1_000L } ?: PLACEHOLDER_CHAPTER_DURATION_MS),
        fileLastModified = Instant.EPOCH,
        fileSize = 0,
        markData = emptyList(),
      )
    }
    return Book(assembled.content, dataChapters)
  }

  /**
   * Looks up the shelf/stash entry and chapter list needed to play [bookId].
   * Shared by [book] and [content] so prepare does not pay for a full chapter
   * list hydrate only to throw the chapters away.
   */
  private suspend fun assembleOnline(bookId: BookId): AssembledOnline? {
    val bookRef = OnlineUri.parseBookUri(bookId.value) ?: return null
    // shelf first: it is the fresher persisted copy. The search stash is only
    // a fallback for books never added - and it must be peeked, not removed:
    // both the player and the player screen assemble the book, a one-shot
    // stash would starve whichever runs second (search playback that never
    // starts, or a black player screen).
    val onlineBook = service.shelfBook(bookRef.key)
      ?: pendingBooks[bookRef.key]
      ?: return null
    rememberAssembled(bookRef.key, onlineBook)
    val chapters = onlineBook.chapters.ifEmpty {
      runCatching { service.chapters(bookRef.source, bookRef.bookId) }.getOrDefault(emptyList())
    }
    if (chapters.isEmpty()) return null

    val chapterIds = chapters.map { ChapterId(OnlineUri.build(bookRef.source, bookRef.bookId, it.id)) }
    val (startIndex, startPosition) = synchronized(stateLock) {
      val pendingIndex = pendingStarts[bookRef.key]
        ?.let { pending -> chapters.indexOfFirst { it.id == pending } }
      val saved = positions[bookRef.key]
      // the position persisted on the shelf entry survives process death; the
      // session maps win because they are fresher
      val persisted = onlineBook.currentChapterId.takeIf { it.isNotBlank() && onlineBook.positionMs >= 0L }
      when {
        pendingIndex != null && pendingIndex >= 0 -> pendingIndex to 0L
        saved != null && chapters.any { it.id == saved.chapterId } -> {
          chapters.indexOfFirst { it.id == saved.chapterId } to saved.positionMs
        }
        persisted != null && chapters.any { it.id == persisted } -> {
          chapters.indexOfFirst { it.id == persisted } to onlineBook.positionMs
        }
        else -> 0 to 0L
      }
    }

    val content = BookContent(
      id = bookId,
      playbackSpeed = 1f,
      skipSilence = false,
      isActive = true,
      lastPlayedAt = Instant.ofEpochMilli(onlineBook.addedAt),
      author = onlineBook.author.ifBlank { null },
      name = onlineBook.title,
      addedAt = Instant.ofEpochMilli(onlineBook.addedAt),
      chapters = chapterIds,
      currentChapter = chapterIds[startIndex],
      positionInChapter = startPosition.coerceAtLeast(0L),
      cover = null,
      gain = 0f,
      genre = null,
      narrator = null,
      series = null,
      part = null,
      skipIntro = onlineBook.skipIntroMs,
      skipOutro = onlineBook.skipOutroMs,
    )
    return AssembledOnline(bookRef = bookRef, content = content, chapters = chapters)
  }

  private data class AssembledOnline(
    val bookRef: OnlineBookRef,
    val content: BookContent,
    val chapters: List<OnlineChapter>,
  )

  /**
   * Builds a display [Book] purely from the locally stored [OnlineBook] (no
   * network), so the shelf can show online books. Playback still goes through
   * [book], which may fetch fresher chapters.
   */
  public fun localBook(onlineBook: OnlineBook): Book {
    val bookRef = OnlineBookRef(onlineBook.source, onlineBook.bookId)
    val bookId = BookId(OnlineUri.buildBookUri(onlineBook.source, onlineBook.bookId))
    val chapters = onlineBook.chapters.ifEmpty {
      listOf(OnlineChapter(id = onlineBook.bookId, title = onlineBook.title, durationSeconds = 0, order = 1))
    }
    val chapterIds = chapters.map { ChapterId(OnlineUri.build(bookRef.source, bookRef.bookId, it.id)) }
    // the persisted position feeds the shelf card progress; without it a
    // resumed book would always show "not started" until it was played again
    val persistedIndex = onlineBook.currentChapterId.takeIf { it.isNotBlank() }
      ?.let { id -> chapters.indexOfFirst { it.id == id } }
      ?.takeIf { it >= 0 }
    val content = BookContent(
      id = bookId,
      playbackSpeed = 1f,
      skipSilence = false,
      isActive = false,
      lastPlayedAt = Instant.ofEpochMilli(onlineBook.addedAt),
      author = onlineBook.author.ifBlank { null },
      name = onlineBook.title,
      addedAt = Instant.ofEpochMilli(onlineBook.addedAt),
      chapters = chapterIds,
      currentChapter = chapterIds[persistedIndex ?: 0],
      positionInChapter = persistedIndex?.let { onlineBook.positionMs }?.coerceAtLeast(0L) ?: 0L,
      cover = null,
      gain = 0f,
      genre = null,
      narrator = null,
      series = null,
      part = null,
      skipIntro = onlineBook.skipIntroMs,
      skipOutro = onlineBook.skipOutroMs,
    )
    val dataChapters = chapters.map { chapter ->
      val uri = OnlineUri.build(bookRef.source, bookRef.bookId, chapter.id)
      Chapter(
        id = ChapterId(uri),
        name = chapter.title,
        duration = measuredDurations[uri]
          ?: (chapter.durationSeconds.takeIf { it > 0 }?.let { it * 1_000L } ?: PLACEHOLDER_CHAPTER_DURATION_MS),
        fileLastModified = Instant.EPOCH,
        fileSize = 0,
        markData = emptyList(),
      )
    }
    return Book(content, dataChapters)
  }

  /**
   * Records a duration measured from the real stream (mp3 header analysis at
   * playback start, or the player-reported duration) and persists it, so the
   * next assembly of the book uses the correct value instead of the
   * source-reported or placeholder one. Keyed by the full chapter uri, so
   * every source keeps its own measurements.
   */
  public fun recordMeasuredDuration(
    source: String,
    bookId: String,
    chapterId: String,
    durationMs: Long,
  ) {
    if (durationMs <= 0L) return
    val uri = OnlineUri.build(source, bookId, chapterId)
    if (measuredDurations[uri] == durationMs) return
    measuredDurations[uri] = durationMs
    _durationsVersion.value += 1
    // Memory is enough for the live player; persist off the caller thread
    // (often main) so measuring a chapter never stalls the UI.
    persistenceScope.launch {
      try {
        booksStore.updateData { books ->
          books.map { book ->
            if (book.source != source || book.bookId != bookId) {
              book
            } else {
              book.copy(
                chapters = book.chapters.map { chapter ->
                  if (chapter.id == chapterId) {
                    chapter.copy(durationSeconds = (durationMs / 1_000L).toInt())
                  } else {
                    chapter
                  }
                },
              )
            }
          }
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Logger.w("Failed to persist measured online chapter duration: $e")
      }
    }
  }

  /**
   * The synthesized [BookContent] of an online book, or null when not one.
   * Cheaper than [book]: prepare only needs content (id, chapters ids, position),
   * so it skips building every [Chapter] row.
   */
  public suspend fun content(bookId: BookId): BookContent? {
    return assembleOnline(bookId)?.content
  }

  /**
   * Resolves a playable url for one online chapter. Runs on the playback
   * loading thread; network failures are reported through [playbackErrors]
   * and answered with null (which fails the load with a 404).
   *
   * A url that was resolved shortly before is reused, and concurrent callers
   * for the same chapter share one in-flight resolve so a cold open does not
   * pay the download/API cost twice.
   */
  public suspend fun resolveStreamUrl(ref: OnlineChapterRef): String? {
    val chapterUri = OnlineUri.build(ref.source, ref.bookId, ref.chapterId)
    cachedStreamUrl(chapterUri)?.let { return it }

    while (true) {
      val existing = inFlightResolves[chapterUri]
      if (existing != null) {
        return existing.await()
      }
      val deferred = CompletableDeferred<String?>()
      val winner = inFlightResolves.putIfAbsent(chapterUri, deferred)
      if (winner != null) {
        return winner.await()
      }

      // capture after we own the slot so a concurrent invalidate that ran
      // before putIfAbsent does not make a fresh resolve look stale
      val epochAtStart = streamUrlEpoch[chapterUri] ?: 0
      val bookUri = OnlineUri.buildBookUri(ref.source, ref.bookId)
      beginResolving(bookUri)
      try {
        val resolved = resolveStreamUrlInternal(ref)
        if (resolved != null && (streamUrlEpoch[chapterUri] ?: 0) == epochAtStart) {
          // skip caching when invalidateStreamUrl raced this resolve: the url
          // may already have been rejected by the data source
          synchronized(stateLock) {
            if ((streamUrlEpoch[chapterUri] ?: 0) == epochAtStart) {
              streamUrls[chapterUri] = CachedStreamUrl(resolved, SystemClock.elapsedRealtime())
            }
          }
        }
        deferred.complete(resolved)
        return resolved
      } catch (e: CancellationException) {
        deferred.cancel(e)
        throw e
      } catch (e: Exception) {
        Logger.w(e, "Online stream resolve failed for $chapterUri")
        deferred.complete(null)
        return null
      } finally {
        endResolving(bookUri)
        inFlightResolves.remove(chapterUri, deferred)
      }
    }
  }

  /**
   * Drops the url cached for [ref]. The data source calls this when the server
   * rejects a url: direct links are signed and expire, so the next resolve has
   * to ask the source for a fresh one.
   *
   * Always returns true so [OnlineStreamingDataSource] retries once: a resolve
   * that raced an earlier invalidate may have handed out a url without putting
   * it in the cache, and waiters of a cancelled in-flight job need the same
   * second chance as a plain cache hit.
   */
  public fun invalidateStreamUrl(ref: OnlineChapterRef): Boolean {
    val chapterUri = OnlineUri.build(ref.source, ref.bookId, ref.chapterId)
    // bump the epoch before clearing so an in-flight resolve that still holds
    // the rejected url cannot publish it back into the cache
    streamUrlEpoch.merge(chapterUri, 1) { current, _ -> current + 1 }
    // drop the slot so the retry does not await the doomed in-flight job
    inFlightResolves.remove(chapterUri)
    synchronized(stateLock) {
      streamUrls.remove(chapterUri)
    }
    return true
  }

  private fun cachedStreamUrl(chapterUri: String): String? {
    val cached = synchronized(stateLock) { streamUrls[chapterUri] } ?: return null
    if (SystemClock.elapsedRealtime() - cached.resolvedAt <= STREAM_URL_TTL_MS) {
      return cached.url
    }
    synchronized(stateLock) { streamUrls.remove(chapterUri) }
    return null
  }

  private fun beginResolving(bookUri: String) {
    val resolving = synchronized(stateLock) {
      resolvingCounts[bookUri] = (resolvingCounts[bookUri] ?: 0) + 1
      resolvingCounts.keys.toSet()
    }
    _resolvingBooks.value = resolving
  }

  private fun endResolving(bookUri: String) {
    val resolving = synchronized(stateLock) {
      val remaining = (resolvingCounts[bookUri] ?: 1) - 1
      if (remaining <= 0) {
        resolvingCounts.remove(bookUri)
      } else {
        resolvingCounts[bookUri] = remaining
      }
      resolvingCounts.keys.toSet()
    }
    _resolvingBooks.value = resolving
  }

  private suspend fun resolveStreamUrlInternal(ref: OnlineChapterRef): String? {
    return try {
      if (ref.source == OnlineSourceClient.SOURCE_MAIN) {
        resolveMain(ref)
      } else {
        service.resolveDirectUrl(ref.source, ref.bookId, ref.chapterId)
          ?: fail(ref, OnlinePlaybackErrorKind.CONTENT, "The source returned no audio url")
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: OnlineSourceException) {
      fail(
        ref,
        if (e.requiresRelogin) OnlinePlaybackErrorKind.AUTH else OnlinePlaybackErrorKind.NETWORK,
        e.message,
      )
    } catch (e: IOException) {
      fail(ref, OnlinePlaybackErrorKind.NETWORK, e.message)
    } catch (e: Exception) {
      fail(ref, OnlinePlaybackErrorKind.CONTENT, e.message)
    }
  }

  /**
   * Main catalog: stream a file downloaded on the site. Missing episodes are
   * downloaded on demand (batch from the playing episode, so the next chapters
   * are ready when they come up) and polled until the file appears.
   */
  private suspend fun resolveMain(ref: OnlineChapterRef): String? {
    val bookRef = OnlineBookRef(ref.source, ref.bookId)
    // books started from the search dialog live in the session maps, not on
    // the shelf: without them the title below is blank and the file about to
    // be downloaded can never be matched (always "connection failed").
    val onlineBook = lookupOnlineBook(bookRef.source, bookRef.bookId)
    val chapters = onlineBook?.chapters?.takeIf { it.isNotEmpty() }
      ?: service.chapters(ref.source, ref.bookId)
    if (chapters.isEmpty()) {
      return fail(ref, OnlinePlaybackErrorKind.CONTENT, "The book has no chapters")
    }
    val index = chapters.indexOfFirst { it.id == ref.chapterId }
    if (index < 0) {
      return fail(ref, OnlinePlaybackErrorKind.CONTENT, "The chapter is not part of the book")
    }
    // The main catalog's download API addresses episodes by their position in
    // the album, not by the first number found in the display title. Titles
    // often contain volume/year/bonus numbers, and using those numbers makes
    // the list look correct while playing a different file.
    val episode = chapters[index].order.takeIf { it > 0 } ?: (index + 1)
    val title = onlineBook?.title.orEmpty()

    // Preferred: ask the server to only resolve the audio url. Nothing gets
    // downloaded on the server, so playback starts immediately instead of
    // waiting for a whole file (and the server disk stays clean). Older
    // servers without the endpoint, or a refused url, fall through to the
    // download + poll path below.
    runCatching { service.resolveStreamUrlDirect(ref.bookId, episode) }
      .getOrNull()
      ?.takeIf { it.isNotBlank() }
      ?.let { return it }

    val endEpisode = (episode + DOWNLOAD_AHEAD).coerceAtMost(chapters.size)

    val deadline = SystemClock.elapsedRealtime() + RESOLVE_TIMEOUT_MS
    var taskId: String? = null
    var submitAttempted = false
    while (true) {
      findDownloadedFile(ref.bookId, ref.chapterId, title, episode)?.let { return it }
      if (SystemClock.elapsedRealtime() >= deadline) break
      if (!submitAttempted) {
        submitAttempted = true
        // The site owns one download slot per card. A 409 here means another
        // task holds it - often this book's manual cache window (which paces
        // one episode per server task) or the local plugin. That is not a
        // failure of this chapter: keep polling for the file; the deadline
        // still bounds the wait. Any other error fails the resolve as before.
        try {
          taskId = service.submitDownload(ref.bookId, episode, endEpisode)
        } catch (e: OnlineSourceException) {
          if (e.httpCode != 409) throw e
        }
        if (taskId == null) {
          return fail(
            ref,
            OnlinePlaybackErrorKind.CONTENT,
            "The server rejected the download request (no task id)",
          )
        }
      } else if (taskId != null) {
        val status = runCatching { service.downloadStatus(taskId) }.getOrNull()
        if (status != null && status.isFailed) {
          return fail(
            ref,
            OnlinePlaybackErrorKind.CONTENT,
            status.error ?: "Download task failed",
          )
        }
      }
      delay(POLL_INTERVAL_MS)
    }
    return fail(ref, OnlinePlaybackErrorKind.NETWORK, "The download did not finish in time")
  }

  /** The streaming url of the downloaded file for [chapterId], or null. */
  private suspend fun findDownloadedFile(
    bookId: String,
    chapterId: String,
    bookTitle: String,
    episode: Int,
  ): String? {
    val albums = cachedDownloadedAlbums() ?: return null

    // Exact identity first: the site records album_id/track_id per downloaded
    // file, so the chapter can be found without any guessing. A track id is
    // globally unique, so this can never pick another book's audio.
    if (chapterId.isNotEmpty()) {
      val byId = albums
        .filter { it.albumId.isEmpty() || it.albumId == bookId }
        .firstNotNullOfOrNull { album ->
          album.files.firstOrNull { it.trackId == chapterId }?.let { file ->
            album to file
          }
        }
      if (byId != null) return service.downloadedFileUrl(byId.second.path)
    }

    // Legacy files without recorded ids fall back to title + episode matching.
    val candidates = albums.mapNotNull { album ->
      val file = album.files
        .filter { fileMatchesEpisode(it.name, episode) }
        .minByOrNull { it.name.length }
        ?: return@mapNotNull null
      album to file
    }
    if (candidates.isEmpty()) return null
    // Only an album whose name matches the book may be streamed. Every album
    // numbers its files 第N集, so a blind pick would play another book's file
    // whenever the right album has no file for this episode yet (the download
    // is still running, or the source only serves the book's free preview).
    // The resolve loop keeps polling until the correct file appears instead.
    val album = candidates
      .filter { titleSimilar(it.first.name, bookTitle) }
      .maxByOrNull { titleOverlap(it.first.name, bookTitle) }
      ?.first
      ?: return null
    val file = album.files
      .filter { fileMatchesEpisode(it.name, episode) }
      .minByOrNull { it.name.length }
      ?: return null
    return service.downloadedFileUrl(file.path)
  }

  private suspend fun cachedDownloadedAlbums(): List<FilesAlbum>? {
    val now = SystemClock.elapsedRealtime()
    synchronized(stateLock) {
      albumsCache?.takeIf { now - it.first < ALBUMS_CACHE_MS }?.let { return it.second }
    }
    val albums = runCatching { service.downloadedAlbums() }.getOrNull() ?: return null
    synchronized(stateLock) {
      albumsCache = now to albums
    }
    return albums
  }

  /** Remembers an assembled book so the resolve step can still see its title. */
  private fun rememberAssembled(
    key: String,
    book: OnlineBook,
  ) {
    if (assembledBooks.size >= STASH_CAP) {
      assembledBooks.keys.firstOrNull()?.let { assembledBooks.remove(it) }
    }
    assembledBooks[key] = book
  }

  private fun fail(
    ref: OnlineChapterRef,
    kind: OnlinePlaybackErrorKind,
    detail: String?,
  ): String? {
    Logger.w("Online playback resolution failed for $ref: $detail")
    val now = SystemClock.elapsedRealtime()
    val dedupeKey = "${ref.source}::${ref.bookId}::${ref.chapterId}::$kind"
    synchronized(stateLock) {
      if (dedupeKey == lastErrorKey && now - lastErrorAt < ERROR_DEDUPE_MS) return null
      lastErrorKey = dedupeKey
      lastErrorAt = now
    }
    val error = OnlinePlaybackError(
      bookUri = OnlineUri.buildBookUri(ref.source, ref.bookId),
      chapterId = ref.chapterId,
      kind = kind,
      detail = detail,
    )
    if (!_playbackErrors.tryEmit(error)) {
      Logger.w("Dropped an online playback error, the previous one is still uncollected")
    }
    return null
  }

  private data class OnlinePosition(
    val chapterId: String,
    val positionMs: Long,
  )

  private data class CachedStreamUrl(
    val url: String,
    val resolvedAt: Long,
  )

  public companion object {

    /** When playing episode k, the server keeps downloading up to k + 3. */
    internal const val DOWNLOAD_AHEAD: Int = 3

    /** When the position of a book was persisted last, so writes can be throttled. */
    private const val POSITION_PERSIST_INTERVAL_MS = 3_000L
    private const val RESOLVE_TIMEOUT_MS = 90_000L
    private const val POLL_INTERVAL_MS = 1_500L

    /**
     * Keep the album list cache short during resolve polling: the first play
     * must notice as soon as the server finishes the download.
     */
    private const val ALBUMS_CACHE_MS = 1_000L

    private const val ERROR_DEDUPE_MS = 30_000L
    private const val PLACEHOLDER_CHAPTER_DURATION_MS = 30 * 60_000L

    /**
     * How long a resolved url is reused before the source is asked again.
     * A chapter usually lasts longer than this, so the previous five minutes
     * expired exactly the url the prefetcher had warmed for the *next* chapter
     * - every chapter start then paid a fresh resolve, which is what the
     * warming was for. An expired url is not a dead end anyway: the data source
     * reports the rejection, the entry is invalidated and the resolve runs
     * again.
     */
    private const val STREAM_URL_TTL_MS = 30 * 60_000L

    /** Resolved urls kept for seeks; the lru drops the oldest beyond this. */
    private const val MAX_CACHED_STREAM_URLS = 16

    /** Session stash is user-tap driven; the cap only guards runaway growth. */
    private const val STASH_CAP = 64

    /** The first digit run of the title, falling back to the playlist position. */
    internal fun episodeNumber(
      chapterTitle: String,
      fallbackIndex: Int,
    ): Int {
      val match = Regex("\\d+").find(chapterTitle) ?: return fallbackIndex + 1
      return match.value.toIntOrNull() ?: fallbackIndex + 1
    }

    /** True when the file name carries exactly the episode number. */
    internal fun fileMatchesEpisode(
      fileName: String,
      episode: Int,
    ): Boolean {
      val expected = episode.toString()
      return Regex("\\d+").findAll(fileName)
        .any { it.value.trimStart('0').ifEmpty { "0" } == expected }
    }

    /** Loose containment match: the site folder name may decorate the title. */
    internal fun titleSimilar(
      albumName: String,
      bookTitle: String,
    ): Boolean {
      val album = albumName.filter { it.isLetterOrDigit() }.lowercase()
      val title = bookTitle.filter { it.isLetterOrDigit() }.lowercase()
      if (album.isEmpty() || title.isEmpty()) return false
      return album in title || title in album
    }

    /**
     * How strongly two names overlap: the longest shared character run, after
     * the same normalization [titleSimilar] uses. Among several albums whose
     * names contain the title (e.g. two recordings of one series), this picks
     * the closest folder instead of the first in server order.
     */
    internal fun titleOverlap(
      albumName: String,
      bookTitle: String,
    ): Int {
      val album = albumName.filter { it.isLetterOrDigit() }.lowercase()
      val title = bookTitle.filter { it.isLetterOrDigit() }.lowercase()
      if (album.isEmpty() || title.isEmpty()) return 0
      val short = if (album.length <= title.length) album else title
      val long = if (album.length <= title.length) title else album
      for (length in short.length downTo 1) {
        for (start in 0..short.length - length) {
          if (long.contains(short.substring(start, start + length))) return length
        }
      }
      return 0
    }
  }
}
