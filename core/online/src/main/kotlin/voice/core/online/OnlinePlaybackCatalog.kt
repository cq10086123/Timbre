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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
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
 * - resolves chapter uris to the direct streaming urls the sources hand out.
 */
@Inject
@SingleIn(AppScope::class)
public class OnlinePlaybackCatalog(
  private val service: OnlineSourceService,
  @OnlineSourceBooksStore private val booksStore: DataStore<List<OnlineBook>>,
  private val chapterStore: OnlineChapterStore,
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
   * Entries are also consulted wherever the shelf lookup misses for books
   * that were never added.
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
  private val inFlightResolves = ConcurrentHashMap<String, Deferred<ResolvedStream?>>()

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

  private val _playbackErrors = MutableSharedFlow<OnlinePlaybackError>(extraBufferCapacity = 1)

  /** Playback resolution failures, deduplicated, for the UI to surface. */
  public val playbackErrors: SharedFlow<OnlinePlaybackError> = _playbackErrors

  private var lastErrorKey: String? = null
  private var lastErrorAt = 0L

  /**
   * How long a resolved url stays valid, widened by the preload settings when
   * their chain takes longer than the default: a preload of 10 chapters one
   * minute apart would otherwise have the first urls expire before playback
   * reaches them.
   */
  @Volatile
  public var cacheTtlMs: Long = STREAM_URL_TTL_MS

  /** True when [bookId] addresses a book of the online source. */
  public fun isOnlineBookId(bookId: BookId): Boolean {
    return OnlineUri.parseBookUri(bookId.value) != null
  }

  /**
   * The books on the online shelf, with their persisted chapter lists merged
   * in from [chapterStore] (the shelf record itself stays metadata-only since
   * the chapter split). Emits on every position, chapter or settings change.
   */
  public fun shelfBooks(): Flow<List<OnlineBook>> {
    return combine(booksStore.data, chapterStore.loadedChapters) { books, chapters ->
      books.map { book ->
        if (book.chapters.isEmpty()) {
          chapters[book.key]?.let { stored -> book.copy(chapters = stored) } ?: book
        } else {
          book
        }
      }
    }
  }

  /**
   * Persists [transform] onto the online book everywhere it lives: the pending
   * and assembled in-memory copies and the shelf DataStore. The per-book
   * playback settings (skip intro/outro, skip silence, speed, gain) of an
   * online book live here - the book has no Room row to persist them to.
   */
  private suspend fun mutatePersistedBook(
    bookId: BookId,
    transform: (OnlineBook) -> OnlineBook,
  ) {
    val bookRef = OnlineUri.parseBookUri(bookId.value) ?: return
    pendingBooks[bookRef.key]?.let { pendingBooks[bookRef.key] = transform(it) }
    assembledBooks[bookRef.key]?.let { assembledBooks[bookRef.key] = transform(it) }
    runCatching {
      booksStore.updateData { books ->
        books.map { book ->
          if (book.key == bookRef.key) transform(book) else book
        }
      }
    }.onFailure { Logger.w("Failed to persist an online book setting of ${bookRef.key}: $it") }
  }

  /** Persists the intro skip of an online book (in milliseconds). */
  public suspend fun setSkipIntro(
    bookId: BookId,
    skipMs: Long,
  ) {
    mutatePersistedBook(bookId) { it.copy(skipIntroMs = skipMs.coerceAtLeast(0L)) }
  }

  /** Persists the outro skip of an online book (in milliseconds). */
  public suspend fun setSkipOutro(
    bookId: BookId,
    skipMs: Long,
  ) {
    mutatePersistedBook(bookId) { it.copy(skipOutroMs = skipMs.coerceAtLeast(0L)) }
  }

  /** Persists whether silence skipping is enabled for an online book. */
  public suspend fun setSkipSilence(
    bookId: BookId,
    skipSilence: Boolean,
  ) {
    mutatePersistedBook(bookId) { it.copy(skipSilence = skipSilence) }
  }

  /** Persists the playback speed of an online book. */
  public suspend fun setPlaybackSpeed(
    bookId: BookId,
    speed: Float,
  ) {
    mutatePersistedBook(bookId) { it.copy(playbackSpeed = speed) }
  }

  /** Persists the volume gain of an online book in decibels. */
  public suspend fun setGain(
    bookId: BookId,
    gain: Float,
  ) {
    mutatePersistedBook(bookId) { it.copy(gain = gain) }
  }

  /**
   * Re-fetches the chapter list of [bookId] from the source and stores it.
   * Durations measured from real streams, the playback position and the skip
   * settings survive the update; a failed or empty fetch keeps the stored
   * chapters untouched so playback continues with them.
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
    var added = 0
    val freshIds = fresh.map { it.id }.toSet()
    // the chapter the user was listening to may have vanished from the
    // source: reset the stored position instead of pointing at nothing
    val positionSurvives = shelf.currentChapterId.isBlank() || shelf.currentChapterId in freshIds
    // merged against the freshest stored list under the store lock, so a
    // duration measured while the refresh ran is not rolled back
    runCatching {
      chapterStore.update(bookRef.key) { stored ->
        val oldById = stored.associateBy { it.id }
        added = fresh.count { it.id !in oldById }
        fresh.map { chapter ->
          val measured = measuredDurations[OnlineUri.build(bookRef.source, bookRef.bookId, chapter.id)]
          val previous = oldById[chapter.id]
          when {
            measured != null && measured > 0L -> chapter.copy(durationSeconds = (measured / 1_000L).toInt())
            previous != null && chapter.durationSeconds <= 0 && previous.durationSeconds > 0 ->
              chapter.copy(durationSeconds = previous.durationSeconds)
            else -> chapter
          }
        }
      }
    }.onFailure {
      Logger.w("Failed to persist refreshed online chapters of ${bookRef.key}: $it")
      return OnlineChapterRefreshResult.Failed(it.message)
    }
    runCatching {
      booksStore.updateData { books ->
        books.map { book ->
          if (book.key != bookRef.key) {
            book
          } else {
            book.copy(
              currentChapterId = if (positionSurvives) shelf.currentChapterId else "",
              positionMs = if (positionSurvives) shelf.positionMs else 0L,
            )
          }
        }
      }
    }.onFailure {
      Logger.w("Failed to persist the refreshed online position of ${bookRef.key}: $it")
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
      // the player parsed the whole file: its duration is the ground truth
      // and must replace a polluted estimate from an earlier head probe
      recordMeasuredDuration(
        source = bookRef.source,
        bookId = bookRef.bookId,
        chapterId = chapterRef.chapterId,
        durationMs = durationMs,
        persistOverExisting = true,
      )
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
  public suspend fun lookupOnlineBook(
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
    // session copies (search stash, in-flight refresh) carry their list inline;
    // shelf records keep only metadata and read the list from chapterStore,
    // falling back to the source when neither has one
    val chapters = onlineBook.chapters.ifEmpty {
      runCatching { chapterStore.chapters(bookRef.key) }.getOrDefault(emptyList())
    }.ifEmpty {
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
      playbackSpeed = onlineBook.playbackSpeed,
      skipSilence = onlineBook.skipSilence,
      isActive = true,
      lastPlayedAt = Instant.ofEpochMilli(onlineBook.addedAt),
      author = onlineBook.author.ifBlank { null },
      name = onlineBook.title,
      addedAt = Instant.ofEpochMilli(onlineBook.addedAt),
      chapters = chapterIds,
      currentChapter = chapterIds[startIndex],
      positionInChapter = startPosition.coerceAtLeast(0L),
      cover = null,
      gain = onlineBook.gain,
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
   * Builds a display [Book] purely from locally stored data (no network), so
   * the shelf can show online books. [chapters] is the persisted list from
   * [OnlineChapterStore]; the record itself only carries metadata since the
   * chapter split. Playback still goes through [book], which may fetch
   * fresher chapters.
   */
  public fun localBook(
    onlineBook: OnlineBook,
    chapters: List<OnlineChapter> = emptyList(),
  ): Book {
    val bookRef = OnlineBookRef(onlineBook.source, onlineBook.bookId)
    val bookId = BookId(OnlineUri.buildBookUri(onlineBook.source, onlineBook.bookId))
    val chapterList = chapters.ifEmpty { onlineBook.chapters }.ifEmpty {
      listOf(OnlineChapter(id = onlineBook.bookId, title = onlineBook.title, durationSeconds = 0, order = 1))
    }
    val chapterIds = chapterList.map { ChapterId(OnlineUri.build(bookRef.source, bookRef.bookId, it.id)) }
    // the persisted position feeds the shelf card progress; without it a
    // resumed book would always show "not started" until it was played again
    val persistedIndex = onlineBook.currentChapterId.takeIf { it.isNotBlank() }
      ?.let { id -> chapterList.indexOfFirst { it.id == id } }
      ?.takeIf { it >= 0 }
    val content = BookContent(
      id = bookId,
      playbackSpeed = onlineBook.playbackSpeed,
      skipSilence = onlineBook.skipSilence,
      isActive = false,
      lastPlayedAt = Instant.ofEpochMilli(onlineBook.addedAt),
      author = onlineBook.author.ifBlank { null },
      name = onlineBook.title,
      addedAt = Instant.ofEpochMilli(onlineBook.addedAt),
      chapters = chapterIds,
      currentChapter = chapterIds[persistedIndex ?: 0],
      positionInChapter = persistedIndex?.let { onlineBook.positionMs }?.coerceAtLeast(0L) ?: 0L,
      cover = null,
      gain = onlineBook.gain,
      genre = null,
      narrator = null,
      series = null,
      part = null,
      skipIntro = onlineBook.skipIntroMs,
      skipOutro = onlineBook.skipOutroMs,
    )
    val dataChapters = chapterList.map { chapter ->
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
   *
   * [persistOverExisting]: the player-reported duration is the ground truth
   * and always overwrites; the head-probe estimate must only fill chapters
   * without a duration - a false mpeg sync in the file head used to overwrite
   * source-reported durations with garbage (a 10min chapter showing 15min)
   * on every chapter switch after a cold start.
   */
  public fun recordMeasuredDuration(
    source: String,
    bookId: String,
    chapterId: String,
    durationMs: Long,
    persistOverExisting: Boolean = false,
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
        val _ = chapterStore.updateChapterDuration(
          key = "$source::$bookId",
          chapterId = chapterId,
          durationSeconds = (durationMs / 1_000L).toInt(),
          overwriteExisting = persistOverExisting,
        )
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
   *
   * [reportErrors] = false keeps resolution failures off [playbackErrors]:
   * background callers (the duration probe-ahead) surface no error for a
   * chapter that is not being listened to right now.
   */
  public suspend fun resolveStreamUrl(
    ref: OnlineChapterRef,
    reportErrors: Boolean = true,
  ): ResolvedStream? {
    val chapterUri = OnlineUri.build(ref.source, ref.bookId, ref.chapterId)
    cachedStreamUrl(chapterUri)?.let { return it }

    while (true) {
      val existing = inFlightResolves[chapterUri]
      if (existing != null) {
        return existing.await()
      }
      val deferred = CompletableDeferred<ResolvedStream?>()
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
        val resolved = resolveStreamUrlInternal(ref, reportErrors)
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

  private fun cachedStreamUrl(chapterUri: String): ResolvedStream? {
    val cached = synchronized(stateLock) { streamUrls[chapterUri] } ?: return null
    if (SystemClock.elapsedRealtime() - cached.resolvedAt <= cacheTtlMs) {
      return cached.stream
    }
    synchronized(stateLock) { streamUrls.remove(chapterUri) }
    return null
  }

  /**
   * How many chapters directly after [ref] hold a live (unexpired) stream url.
   * Counts only the consecutive run after the current chapter - that is the
   * window the preload state machine fills - and stops at [max].
   */
  public suspend fun cachedChaptersAhead(
    ref: OnlineChapterRef,
    max: Int,
  ): Int {
    if (max <= 0) return 0
    val book = lookupOnlineBook(ref.source, ref.bookId) ?: return 0
    val chapters = book.chapters.ifEmpty {
      runCatching { chapterStore.chapters(OnlineBookRef(ref.source, ref.bookId).key) }
        .getOrDefault(emptyList())
    }
    val index = chapters.indexOfFirst { it.id == ref.chapterId }
    if (index < 0) return 0
    var count = 0
    for (chapter in chapters.drop(index + 1)) {
      if (count >= max) break
      val uri = OnlineUri.build(ref.source, ref.bookId, chapter.id)
      val cached = synchronized(stateLock) { streamUrls[uri] } ?: break
      if (SystemClock.elapsedRealtime() - cached.resolvedAt > cacheTtlMs) break
      count++
    }
    return count
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

  private suspend fun resolveStreamUrlInternal(
    ref: OnlineChapterRef,
    reportErrors: Boolean,
  ): ResolvedStream? {
    return try {
      service.resolveDirectUrl(ref.source, ref.bookId, ref.chapterId)
        ?: fail(ref, OnlinePlaybackErrorKind.CONTENT, "The source returned no audio url", reportErrors)
    } catch (e: CancellationException) {
      throw e
    } catch (e: OnlineSourceException) {
      fail(
        ref,
        if (e.requiresRelogin) OnlinePlaybackErrorKind.AUTH else OnlinePlaybackErrorKind.NETWORK,
        e.message,
        reportErrors,
      )
    } catch (e: IOException) {
      fail(ref, OnlinePlaybackErrorKind.NETWORK, e.message, reportErrors)
    } catch (e: Exception) {
      fail(ref, OnlinePlaybackErrorKind.CONTENT, e.message, reportErrors)
    }
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
    reportErrors: Boolean = true,
  ): ResolvedStream? {
    Logger.w("Online playback resolution failed for $ref: $detail")
    if (!reportErrors) return null
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
    val stream: ResolvedStream,
    val resolvedAt: Long,
  )

  public companion object {

    /** When the position of a book was persisted last, so writes can be throttled. */
    private const val POSITION_PERSIST_INTERVAL_MS = 3_000L
    private const val ERROR_DEDUPE_MS = 30_000L
    private const val PLACEHOLDER_CHAPTER_DURATION_MS = 30 * 60_000L

    /** How long a resolved url is reused before the source is asked again. */
    public const val STREAM_URL_TTL_MS: Long = 5 * 60_000L

    /** Resolved urls kept for seeks; the lru drops the oldest beyond this. */
    private const val MAX_CACHED_STREAM_URLS = 16

    /** Session stash is user-tap driven; the cap only guards runaway growth. */
    private const val STASH_CAP = 64
  }
}
