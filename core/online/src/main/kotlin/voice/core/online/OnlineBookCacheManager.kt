package voice.core.online

import androidx.datastore.core.DataStore
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import voice.core.common.DispatcherProvider
import voice.core.data.BookId
import voice.core.logging.api.Logger
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

/** Progress of one manual cache job, keyed by the canonical book uri. */
public data class OnlineCacheState(
  val bookUri: String,
  val total: Int,
  val done: Int,
  val failed: Int,
  val downloading: Boolean,
  val currentTitle: String = "",
)

/** What the cache dialog shows about one book. */
public data class OnlineCachedInfo(
  val bookUri: String,
  val title: String,
  val totalChapters: Int,
  /** 0-based index caching starts from (the chapter in progress). */
  val currentIndex: Int,
  val cachedCount: Int,
  val cachedBytes: Long,
)

/**
 * Manually caches upcoming chapters of an online book onto the device, so
 * they keep playing offline. Playback prefers a cached file over the stream
 * (see [OnlineStreamingDataSource]) and falls back to streaming for chapters
 * that were never cached, so a partially cached book plays seamlessly: cached
 * episodes offline, the rest online.
 *
 * Manual caching never fights automatic playback:
 * - automatic ahead work (the main catalog's server side download window,
 *   the in-memory stream url cache, duration probing) never touches
 *   [OnlineChapterFileCache]; only this manager writes there,
 * - resolving a chapter reuses [OnlinePlaybackCatalog.resolveStreamUrl], whose
 *   single flight coalesces a manual resolve with a playback resolve of the
 *   same chapter instead of downloading twice,
 * - downloads run sequentially through one process wide slot and are plain
 *   background traffic: playback never waits for them.
 */
@SingleIn(AppScope::class)
@Inject
public class OnlineBookCacheManager(
  private val catalog: OnlinePlaybackCatalog,
  private val service: OnlineSourceService,
  private val fileCache: OnlineChapterFileCache,
  @OnlineSourceStreamingClient private val httpClient: OkHttpClient,
  @OnlineSourceBaseUrlStore private val baseUrlStore: DataStore<String>,
  @OnlineSourceTokenStore private val tokenStore: DataStore<String>,
  dispatcherProvider: DispatcherProvider,
) {

  private val scope = CoroutineScope(SupervisorJob() + dispatcherProvider.io)
  private val jobs = ConcurrentHashMap<String, Job>()

  /**
   * Bumped on every [cacheUpcoming] and [clearBook]: a replaced job must not
   * overwrite the state of its successor (its finally would otherwise flip
   * the new job's `downloading` back to false).
   */
  private val generations = ConcurrentHashMap<String, Int>()

  /**
   * Manual downloads share one slot across books: caching two books at once
   * must not saturate the connection playback streams on.
   */
  private val downloadSlots = Semaphore(1)

  private val _states = MutableStateFlow<Map<String, OnlineCacheState>>(emptyMap())

  /** The cache jobs by canonical book uri. */
  public val states: StateFlow<Map<String, OnlineCacheState>> = _states

  /** The cache job of [bookUri], or null when it was never cached this session. */
  public fun stateFor(bookUri: String): Flow<OnlineCacheState?> {
    return states.map { it[bookUri] }
  }

  /** Describes what is cached of [bookId] for the cache dialog. */
  public suspend fun cachedInfo(bookId: BookId): OnlineCachedInfo? {
    val bookRef = OnlineUri.parseBookUri(bookId.value) ?: return null
    val book = catalog.lookupOnlineBook(bookRef.source, bookRef.bookId)
    val chapters = book?.chapters?.takeIf { it.isNotEmpty() }
      ?: runCatching { service.chapters(bookRef.source, bookRef.bookId) }.getOrNull().orEmpty()
    val currentIndex = book?.currentChapterId
      ?.takeIf { id -> id.isNotBlank() }
      ?.let { id -> chapters.indexOfFirst { it.id == id } }
      ?.takeIf { it >= 0 } ?: 0
    return OnlineCachedInfo(
      bookUri = bookId.value,
      title = book?.title.orEmpty(),
      totalChapters = chapters.size,
      currentIndex = currentIndex,
      cachedCount = fileCache.cachedFileCount(bookRef.source, bookRef.bookId),
      cachedBytes = fileCache.cachedBytes(bookRef.source, bookRef.bookId),
    )
  }

  /**
   * Caches the next [count] chapters starting at the chapter in progress
   * (inclusive, so going offline mid-chapter keeps playing). Already cached
   * chapters are skipped. Replaces a running job of the same book.
   */
  public fun cacheUpcoming(
    bookId: BookId,
    count: Int,
  ) {
    val bookRef = OnlineUri.parseBookUri(bookId.value) ?: return
    val generation = (generations[bookId.value] ?: 0) + 1
    generations[bookId.value] = generation
    jobs[bookId.value]?.cancel()
    val job = scope.launch {
      runCaching(bookId, bookRef, count.coerceIn(1, MAX_MANUAL_CACHE), generation)
    }
    jobs[bookId.value] = job
    job.invokeOnCompletion { jobs.remove(bookId.value, job) }
  }

  /** Cancels a running cache job of [bookId]. Chapters cached so far are kept. */
  public fun cancel(bookId: BookId) {
    jobs[bookId.value]?.cancel()
  }

  /**
   * Drops every cached chapter of [bookId] (a running job is stopped first).
   * The book itself and its progress stay on the shelf.
   */
  public suspend fun clearBook(bookId: BookId): Boolean {
    val bookRef = OnlineUri.parseBookUri(bookId.value) ?: return false
    val generation = (generations[bookId.value] ?: 0) + 1
    generations[bookId.value] = generation
    jobs[bookId.value]?.cancel()
    val _ = runCatching { jobs[bookId.value]?.join() }
    val cleared = fileCache.clearBook(bookRef.source, bookRef.bookId)
    updateState(bookId.value, generation) { it.copy(downloading = false) }
    return cleared
  }

  /** Writes only when [generation] is still the latest job of [bookUri]. */
  private fun setState(
    bookUri: String,
    generation: Int,
    state: OnlineCacheState,
  ) {
    if (generations[bookUri] != generation) return
    _states.update { it + (bookUri to state) }
  }

  /** Updates only when [generation] is still the latest job of [bookUri]. */
  private fun updateState(
    bookUri: String,
    generation: Int,
    transform: (OnlineCacheState) -> OnlineCacheState,
  ) {
    if (generations[bookUri] != generation) return
    _states.update { current ->
      val state = current[bookUri] ?: return@update current
      current + (bookUri to transform(state))
    }
  }

  private suspend fun runCaching(
    bookId: BookId,
    bookRef: OnlineBookRef,
    count: Int,
    generation: Int,
  ) {
    val bookUri = bookId.value
    val book = catalog.lookupOnlineBook(bookRef.source, bookRef.bookId)
    val chapters = book?.chapters?.takeIf { it.isNotEmpty() }
      ?: runCatching { service.chapters(bookRef.source, bookRef.bookId) }.getOrNull().orEmpty()
    if (chapters.isEmpty()) {
      setState(bookUri, generation, OnlineCacheState(bookUri, 0, 0, 0, false))
      return
    }
    val startIndex = book?.currentChapterId
      ?.takeIf { id -> id.isNotBlank() }
      ?.let { id -> chapters.indexOfFirst { it.id == id } }
      ?.takeIf { it >= 0 } ?: 0
    val window = chapters.drop(startIndex).take(count)
    // main catalog: ask the server for the whole window up front, so the
    // per-chapter resolve below finds files instead of submitting and polling
    // one small batch per chapter
    if (bookRef.source == OnlineSourceClient.SOURCE_MAIN && window.isNotEmpty()) {
      val _ = runCatching {
        val startEpisode = OnlinePlaybackCatalog.episodeNumber(window.first().title, startIndex)
        val lastEpisode = OnlinePlaybackCatalog.episodeNumber(window.last().title, startIndex + window.size - 1)
        service.submitDownload(bookRef.bookId, startEpisode, lastEpisode.coerceAtLeast(startEpisode))
      }
    }
    setState(bookUri, generation, OnlineCacheState(bookUri, window.size, 0, 0, true))
    try {
      var done = 0
      var failed = 0
      var consecutiveFailures = 0
      for (chapter in window) {
        coroutineContext.ensureActive()
        val ref = OnlineChapterRef(bookRef.source, bookRef.bookId, chapter.id)
        updateState(bookUri, generation) { it.copy(currentTitle = chapter.title) }
        if (fileCache.isCached(ref)) {
          done++
          consecutiveFailures = 0
          updateState(bookUri, generation) { it.copy(done = done, failed = failed) }
          continue
        }
        // resolveStreamUrl reports failures as null and only throws on
        // cancellation, so no runCatching: it would swallow the cancellation
        val url = catalog.resolveStreamUrl(ref)
        if (url.isNullOrBlank()) {
          failed++
          consecutiveFailures++
          updateState(bookUri, generation) { it.copy(done = done, failed = failed) }
          if (consecutiveFailures >= ABORT_AFTER_CONSECUTIVE_FAILURES) break
          continue
        }
        val downloaded = downloadSlots.withPermit { downloadToCache(ref, url) }
        if (downloaded) {
          done++
          consecutiveFailures = 0
        } else {
          failed++
          consecutiveFailures++
        }
        updateState(bookUri, generation) { it.copy(done = done, failed = failed) }
        if (consecutiveFailures >= ABORT_AFTER_CONSECUTIVE_FAILURES) break
      }
    } finally {
      // a cancelled job must not leave the dialog spinning: chapters cached
      // so far are kept and the job simply reports as finished
      updateState(bookUri, generation) { it.copy(downloading = false, currentTitle = "") }
    }
  }

  /** Downloads [url] into the file cache; false when it could not be stored. */
  private suspend fun downloadToCache(
    ref: OnlineChapterRef,
    url: String,
  ): Boolean = withContext(Dispatchers.IO) {
    try {
      val requestUrl = OnlineStreamUrlPolicy.applyCertFallback(url)
      download(requestUrl).use { response ->
        if (!response.isSuccessful) return@withContext false
        val tmp = fileCache.tmpFileFor(ref)
        tmp.parentFile?.mkdirs()
        response.body.byteStream().use { input ->
          FileOutputStream(tmp).use { output ->
            input.copyTo(output)
          }
        }
        if (tmp.length() <= 0L) {
          tmp.delete()
          return@withContext false
        }
        coroutineContext.ensureActive()
        fileCache.completeDownload(ref)
      }
    } catch (e: CancellationException) {
      fileCache.discardTmp(ref)
      throw e
    } catch (e: Exception) {
      Logger.w("Online chapter download failed for $ref: $e")
      fileCache.discardTmp(ref)
      // a call cancelled below surfaces as an IOException: rethrow it as a
      // cancellation instead of counting the chapter as failed
      coroutineContext.ensureActive()
      false
    }
  }

  /**
   * Runs one download request, healing hosts whose TLS certificate is
   * unusable the same way streaming does: after a failed https handshake the
   * address is retried over plain http.
   */
  private suspend fun download(url: String): Response {
    return try {
      executeCancellable(downloadRequest(url))
    } catch (e: IOException) {
      coroutineContext.ensureActive()
      val httpUrl = OnlineStreamUrlPolicy.downgradeToHttp(url)
      if (httpUrl == null || !isCertificateProblem(e)) throw e
      Logger.w("TLS handshake failed for source host, retrying over http: ${e.message}")
      OnlineStreamUrlPolicy.rememberCertBroken(url)
      executeCancellable(downloadRequest(httpUrl))
    }
  }

  /**
   * Executes one call, aborting it when the cache job is cancelled: OkHttp's
   * blocking execute would otherwise hold the job (and a clear right after
   * it) until the whole chapter downloaded.
   */
  private suspend fun executeCancellable(request: Request): Response {
    val call = httpClient.newCall(request)
    // same effect as the internal invokeOnCompletion(onCancelling = true):
    // if this coroutine is cancelled while the thread is blocked inside
    // execute(), the socket is aborted so a clear does not wait for the whole
    // download. Cancelling the call after execute() returned is harmless - the
    // body is streamed later but the completion callback only fires once this
    // coroutine actually finishes, which is after the body was fully read.
    val handle = coroutineContext[Job]!!.invokeOnCompletion { call.cancel() }
    try {
      return call.execute()
    } catch (e: Throwable) {
      handle.dispose()
      throw e
    }
  }

  private suspend fun downloadRequest(url: String): Request {
    val builder = Request.Builder().url(url)
    // the site's file streaming endpoint requires the bearer token; third
    // party cdn links must not receive it
    val base = baseUrlStore.data.first().trim().trimEnd('/')
    val token = tokenStore.data.first().trim()
    if (token.isNotEmpty() && base.isNotEmpty() && url.startsWith(base)) {
      builder.header("Authorization", "Bearer $token")
    }
    return builder.build()
  }

  private fun isCertificateProblem(e: Throwable): Boolean = generateSequence(e) { it.cause }
    .take(8)
    .any { it is javax.net.ssl.SSLException || it is java.security.cert.CertificateException }

  private companion object {
    /** Upper bound of one manual cache job; the window is capped by the book anyway. */
    const val MAX_MANUAL_CACHE = 10_000

    /** A dead network fails fast instead of grinding through the whole window. */
    const val ABORT_AFTER_CONSECUTIVE_FAILURES = 5
  }
}
