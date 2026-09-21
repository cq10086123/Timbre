package voice.core.online

import android.os.SystemClock
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import voice.core.data.Book
import voice.core.data.BookContent
import voice.core.data.BookId
import voice.core.data.Chapter
import voice.core.data.ChapterId
import voice.core.logging.api.Logger
import java.io.IOException
import java.time.Instant

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
public class OnlinePlaybackCatalog(private val service: OnlineSourceService) {

  private val stateLock = Any()

  /** Chapter the user tapped in the search dialog, keyed by [OnlineBookRef.key]. */
  private val pendingStarts = mutableMapOf<String, String>()

  /** Last known playback position per book key, so re-assembly resumes. */
  private val positions = mutableMapOf<String, OnlinePosition>()

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
  ) {
    val bookRef = OnlineUri.parseBookUri(bookId.value) ?: return
    val chapterRef = OnlineUri.parse(chapterId.value) ?: return
    synchronized(stateLock) {
      pendingStarts.remove(bookRef.key)
      positions[bookRef.key] = OnlinePosition(chapterRef.chapterId, positionMs)
    }
  }

  /** Synthesizes the [Book] of an online book, or null when [bookId] is not one. */
  public suspend fun book(bookId: BookId): Book? {
    val bookRef = OnlineUri.parseBookUri(bookId.value) ?: return null
    val onlineBook = service.shelfBook(bookRef.key) ?: return null
    val chapters = onlineBook.chapters.ifEmpty {
      runCatching { service.chapters(bookRef.source, bookRef.bookId) }.getOrDefault(emptyList())
    }
    if (chapters.isEmpty()) return null

    val chapterIds = chapters.map { ChapterId(OnlineUri.build(bookRef.source, bookRef.bookId, it.id)) }
    val (startIndex, startPosition) = synchronized(stateLock) {
      val pendingIndex = pendingStarts[bookRef.key]
        ?.let { pending -> chapters.indexOfFirst { it.id == pending } }
      val saved = positions[bookRef.key]
      when {
        pendingIndex != null && pendingIndex >= 0 -> pendingIndex to 0L
        saved != null && chapters.any { it.id == saved.chapterId } -> {
          chapters.indexOfFirst { it.id == saved.chapterId } to saved.positionMs
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
    )
    val dataChapters = chapters.map { chapter ->
      Chapter(
        id = ChapterId(OnlineUri.build(bookRef.source, bookRef.bookId, chapter.id)),
        name = chapter.title,
        // the stream itself reports the real duration; this only feeds the
        // chapter timeline and must never clip playback to nothing
        duration = (chapter.durationSeconds * 1_000L).coerceAtLeast(MIN_CHAPTER_DURATION_MS),
        fileLastModified = Instant.EPOCH,
        fileSize = 0,
        markData = emptyList(),
      )
    }
    return Book(content, dataChapters)
  }

  /** The synthesized [BookContent] of an online book, or null when not one. */
  public suspend fun content(bookId: BookId): BookContent? {
    return book(bookId)?.content
  }

  /**
   * Resolves a playable url for one online chapter. Runs on the playback
   * loading thread; network failures are reported through [playbackErrors]
   * and answered with null (which fails the load with a 404).
   */
  public suspend fun resolveStreamUrl(ref: OnlineChapterRef): String? {
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
    val onlineBook = service.shelfBook(bookRef.key)
    val chapters = onlineBook?.chapters?.takeIf { it.isNotEmpty() }
      ?: service.chapters(ref.source, ref.bookId)
    if (chapters.isEmpty()) {
      return fail(ref, OnlinePlaybackErrorKind.CONTENT, "The book has no chapters")
    }
    val index = chapters.indexOfFirst { it.id == ref.chapterId }
    if (index < 0) {
      return fail(ref, OnlinePlaybackErrorKind.CONTENT, "The chapter is not part of the book")
    }
    val episode = episodeNumber(chapters[index].title, index)
    val title = onlineBook?.title.orEmpty()
    val endEpisode = (episode + DOWNLOAD_AHEAD).coerceAtMost(chapters.size)

    val deadline = SystemClock.elapsedRealtime() + RESOLVE_TIMEOUT_MS
    var taskId: String? = null
    while (true) {
      findDownloadedFile(title, episode)?.let { return it }
      if (SystemClock.elapsedRealtime() >= deadline) break
      if (taskId == null) {
        taskId = service.submitDownload(ref.bookId, episode, endEpisode)
          ?: break
      } else {
        val status = runCatching { service.downloadStatus(taskId) }.getOrNull()
        if (status != null && status.isFailed) break
      }
      delay(POLL_INTERVAL_MS)
    }
    return fail(ref, OnlinePlaybackErrorKind.NETWORK, "The download did not finish in time")
  }

  /** The streaming url of the downloaded file for [episode], or null. */
  private suspend fun findDownloadedFile(
    bookTitle: String,
    episode: Int,
  ): String? {
    val albums = cachedDownloadedAlbums() ?: return null
    val album = albums.firstOrNull { titleSimilar(it.name, bookTitle) }
      ?: albums.singleOrNull { album -> album.files.any { fileMatchesEpisode(it.name, episode) } }
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

  public companion object {

    /** When playing episode k, the server keeps downloading up to k + 3. */
    internal const val DOWNLOAD_AHEAD: Int = 3

    private const val RESOLVE_TIMEOUT_MS = 90_000L
    private const val POLL_INTERVAL_MS = 1_500L
    private const val ALBUMS_CACHE_MS = 10_000L
    private const val ERROR_DEDUPE_MS = 30_000L
    private const val MIN_CHAPTER_DURATION_MS = 1_000L

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
  }
}
