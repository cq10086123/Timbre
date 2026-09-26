package voice.core.online

import androidx.datastore.core.DataStore
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import voice.core.common.DispatcherProvider
import voice.core.common.PlaybackIoGate
import voice.core.data.BookId
import voice.core.logging.api.Logger
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext

/** Progress of one manual cache job, keyed by the canonical book uri. */
public data class OnlineCacheState(
  val bookUri: String,
  val total: Int,
  val done: Int,
  val failed: Int,
  val downloading: Boolean,
  val currentTitle: String = "",
  /**
   * The job is alive but waits for the user to allow caching on mobile data.
   * The progress bar stays visible, the chapter count is just frozen.
   */
  val awaitingConfirmation: Boolean = false,
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
 * A job that wants to spend mobile data and needs the user's blessing first.
 * The UI shows one of these as a dialog; until it is answered the job does not
 * touch the network at all.
 */
public data class OnlineCacheConfirmation(
  val bookUri: String,
  val title: String,
  /** Chapters this run wants to cache. */
  val chapters: Int,
)

/**
 * Manually caches upcoming chapters of an online book onto the device, so
 * they keep playing offline. Playback prefers a cached file over the stream
 * (see [OnlineStreamingDataSource]) and falls back to streaming for chapters
 * that were never cached, so a partially cached book plays seamlessly: cached
 * episodes offline, the rest online.
 *
 * Manual caching never fights automatic playback:
 * - automatic ahead work (the in-memory stream url cache, duration probing)
 *   never touches [OnlineChapterFileCache]; only this manager writes there,
 * - resolving a chapter reuses [OnlinePlaybackCatalog.resolveStreamUrl], whose
 *   single flight coalesces a manual resolve with a playback resolve of the
 *   same chapter instead of downloading twice,
 * - downloads run sequentially through one process wide slot, yield while the
 *   player buffers ([PlaybackIoGate]) and are plain background traffic:
 *   playback never waits for them.
 *
 * A job is more than a coroutine: its request is persisted in
 * [OnlineCacheJobsStore] before it starts, so a process that dies mid download
 * (or an app the user swiped away) resumes the work on the next start - see
 * [resumePending]. A finished, a cancelled and a declined job is dropped, and
 * so is one that keeps ending its runs without a single chapter (dead source),
 * while a job that keeps downloading is never given up on.
 *
 * Mobile data is never spent silently: whenever a job is about to touch the
 * network on a metered connection it asks through [meteredConfirmation] first
 * and waits. Consent covers one job run, so a cache that ran on wifi asks
 * again when the app restarts on mobile data.
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
  @OnlineCacheJobsStore private val jobsStore: DataStore<List<OnlineCacheJob>>,
  private val meteredNetworkChecker: MeteredNetworkChecker,
  private val playbackIoGate: PlaybackIoGate,
  private val dispatcherProvider: DispatcherProvider,
) {

  private val scope = CoroutineScope(SupervisorJob() + dispatcherProvider.io)
  private val jobs = ConcurrentHashMap<String, Job>()

  /**
   * Bumped whenever a job of a book is replaced or stopped ([start], [cancel],
   * [clearBook]). A job whose generation is no longer the current one must not
   * download, must not publish progress and must not touch the persisted
   * request - its successor owns all three.
   */
  private val generations = ConcurrentHashMap<String, Int>()

  /**
   * Manual downloads share one slot across books: caching two books at once
   * must not saturate the connection playback streams on.
   */
  private val downloadSlots = Semaphore(1)

  /** Serializes the metered questions: two jobs never stack two prompts. */
  private val confirmationGate = Mutex()
  private val pendingConfirmation = AtomicReference<CompletableDeferred<Boolean>?>(null)
  private val resumeRequested = AtomicBoolean(false)

  private val _states = MutableStateFlow<Map<String, OnlineCacheState>>(emptyMap())

  /** The cache jobs by canonical book uri. */
  public val states: StateFlow<Map<String, OnlineCacheState>> = _states

  private val _meteredConfirmation = MutableStateFlow<OnlineCacheConfirmation?>(null)

  /** The metered question the UI has to answer, or null when there is none. */
  public val meteredConfirmation: StateFlow<OnlineCacheConfirmation?> = _meteredConfirmation

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
    // counting the cached chapters means listing a directory, and the dialog
    // calls this on the main thread
    val (cachedCount, cachedBytes) = withContext(dispatcherProvider.io) {
      fileCache.cachedFileCount(bookRef.source, bookRef.bookId) to fileCache.cachedBytes(bookRef.source, bookRef.bookId)
    }
    return OnlineCachedInfo(
      bookUri = bookId.value,
      title = book?.title.orEmpty(),
      totalChapters = chapters.size,
      currentIndex = currentIndex,
      cachedCount = cachedCount,
      cachedBytes = cachedBytes,
    )
  }

  /**
   * Caches the next [count] chapters starting at the chapter in progress
   * (inclusive, so going offline mid-chapter keeps playing). Already cached
   * chapters are skipped. Replaces a running job of the same book.
   *
   * [delaySeconds] spaces the chapters: the source cools down between episodes
   * instead of being hammered with a whole window up front (which gets rate
   * limited and fails).
   */
  public fun cacheUpcoming(
    bookId: BookId,
    count: Int,
    delaySeconds: Int = 0,
  ) {
    val bookRef = OnlineUri.parseBookUri(bookId.value) ?: return
    val job = OnlineCacheJob(
      bookUri = bookId.value,
      count = count.coerceIn(1, MAX_MANUAL_CACHE),
      delaySeconds = delaySeconds.coerceIn(0, MAX_EPISODE_DELAY_SECONDS),
    )
    remember(job)
    start(bookRef, job)
  }

  /**
   * Picks up the jobs a previous process did not finish; the app calls this
   * once on start. A resumed job asks again when the network is metered, so a
   * cache started on wifi never continues on mobile data unasked.
   */
  public fun resumePending() {
    if (!resumeRequested.compareAndSet(false, true)) return
    scope.launch {
      val pending = runCatching { jobsStore.data.first() }.getOrNull().orEmpty()
      for (job in pending) {
        val bookRef = OnlineUri.parseBookUri(job.bookUri)
        if (bookRef == null) {
          forget(job.bookUri)
          continue
        }
        start(bookRef, job)
      }
    }
  }

  /** The user allows the waiting job to cache over mobile data now. */
  public fun confirmMeteredCache() {
    pendingConfirmation.get()?.complete(true)
  }

  /** The user refuses: the waiting job is dropped, its cached files stay. */
  public fun declineMeteredCache() {
    pendingConfirmation.get()?.complete(false)
  }

  /** Cancels a running cache job of [bookId]. Chapters cached so far are kept. */
  public fun cancel(bookId: BookId) {
    // the bump also stops a job that is still starting up - and it keeps a run
    // that finished concurrently from persisting itself again right after the
    // cancel dropped it
    generations[bookId.value] = (generations[bookId.value] ?: 0) + 1
    jobs[bookId.value]?.cancel()
    // a bar that will never move again only pretends the cache is still
    // running; the files that were already downloaded stay
    _states.update { it - bookId.value }
    // a cancelled job must not come back on the next app start
    forget(bookId.value)
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
    forget(bookId.value)
    return withContext(dispatcherProvider.io) {
      val cleared = fileCache.clearBook(bookRef.source, bookRef.bookId)
      _states.update { it - bookId.value }
      cleared
    }
  }

  /** Writes only when [generation] is still the latest job of [bookUri]. */
  private fun setState(
    bookUri: String,
    generation: Int,
    state: OnlineCacheState,
  ) {
    if (!isCurrent(bookUri, generation)) return
    _states.update { it + (bookUri to state) }
  }

  /** Drops the progress entry of a job that ended without finishing. */
  private fun clearState(
    bookUri: String,
    generation: Int,
  ) {
    if (!isCurrent(bookUri, generation)) return
    _states.update { it - bookUri }
  }

  /** Updates only when [generation] is still the latest job of [bookUri]. */
  private fun updateState(
    bookUri: String,
    generation: Int,
    transform: (OnlineCacheState) -> OnlineCacheState,
  ) {
    if (!isCurrent(bookUri, generation)) return
    _states.update { current ->
      val state = current[bookUri] ?: return@update current
      current + (bookUri to transform(state))
    }
  }

  private fun start(
    bookRef: OnlineBookRef,
    job: OnlineCacheJob,
  ) {
    val bookUri = job.bookUri
    val generation = (generations[bookUri] ?: 0) + 1
    generations[bookUri] = generation
    jobs[bookUri]?.cancel()
    // the dialog shows a progress bar the moment the user taps start, not
    // after the first network round trip; the real window size replaces the
    // requested count as soon as the chapter list is known
    setState(
      bookUri,
      generation,
      OnlineCacheState(
        bookUri = bookUri,
        total = job.count,
        done = 0,
        failed = 0,
        downloading = true,
      ),
    )
    // lazy, so the job is registered before it can possibly complete: a job
    // that finishes eagerly (a declined confirmation) would otherwise be
    // removed from [jobs] before it was ever put there
    val cacheJob = scope.launch(start = CoroutineStart.LAZY) {
      runCaching(bookRef, job, generation)
    }
    jobs[bookUri] = cacheJob
    cacheJob.invokeOnCompletion { jobs.remove(bookUri, cacheJob) }
    cacheJob.start()
  }

  private suspend fun runCaching(
    bookRef: OnlineBookRef,
    job: OnlineCacheJob,
    generation: Int,
  ) {
    val bookUri = job.bookUri
    // a clear (or a newer job of the same book) can win the race against this
    // job's start: then this job must not download a single chapter anymore,
    // and it must not touch the store of the job that replaced it
    if (!isCurrent(bookUri, generation)) return
    // the shelf copy is local: it knows the title and the window without
    // touching the network, which matters because a declined confirmation
    // must not send a single request. A failing store read falls back to the
    // source below instead of killing the job without a word
    val localBook = runCatching { catalog.lookupOnlineBook(bookRef.source, bookRef.bookId) }.getOrNull()
    val localChapters = localBook?.chapters.orEmpty()
    val localStart = startIndex(localBook, localChapters)
    val wanted = localChapters.takeIf { it.isNotEmpty() }?.drop(localStart)?.take(job.count)
    var meteredAllowed = false
    if (meteredNetworkChecker.isMetered()) {
      meteredAllowed = awaitMeteredPermission(
        confirmation = OnlineCacheConfirmation(
          bookUri = bookUri,
          title = localBook?.title.orEmpty(),
          chapters = wanted?.size ?: job.count,
        ),
        bookUri = bookUri,
        generation = generation,
      )
      if (!meteredAllowed) {
        // the user said no: nothing was downloaded, so the bar goes away
        // instead of freezing at zero, and the job is not resumed either
        if (isCurrent(bookUri, generation)) {
          clearState(bookUri, generation)
          forget(bookUri)
        }
        return
      }
    }
    val chapters = localChapters.takeIf { it.isNotEmpty() }
      ?: runCatching { service.chapters(bookRef.source, bookRef.bookId) }.getOrNull().orEmpty()
    if (chapters.isEmpty()) {
      // no chapter list at all (offline, source gone): keep the job so the
      // next start can retry, unless it keeps failing without any progress
      clearState(bookUri, generation)
      persistUnlessGivenUp(bookUri, generation, job, progressed = false)
      return
    }
    val startOffset = startIndex(localBook, chapters)
    val window = chapters.drop(startOffset).take(job.count)
    val doneAtStart = window.count { chapter -> fileCache.isCached(chapterRef(bookRef, chapter)) }
    var done = doneAtStart
    var failed = 0
    var consecutiveFailures = 0
    var pacedAny = false
    var declined = false
    setState(
      bookUri,
      generation,
      OnlineCacheState(bookUri, window.size, done, 0, downloading = true),
    )
    try {
      for ((offset, chapter) in window.withIndex()) {
        coroutineContext.ensureActive()
        if (!isCurrent(bookUri, generation)) return
        val ref = chapterRef(bookRef, chapter)
        if (fileCache.isCached(ref)) continue
        if (meteredNetworkChecker.isMetered() && !meteredAllowed) {
          // the network turned metered while caching: the next chapter is a
          // new data volume decision, so ask before it is downloaded
          meteredAllowed = awaitMeteredPermission(
            confirmation = OnlineCacheConfirmation(
              bookUri = bookUri,
              title = localBook?.title.orEmpty(),
              chapters = window.size - offset,
            ),
            bookUri = bookUri,
            generation = generation,
          )
          if (!meteredAllowed) {
            declined = true
            break
          }
        }
        updateState(bookUri, generation) { it.copy(currentTitle = chapter.title) }
        // Pace the chapters: the configured per-episode delay lets the source
        // cool down between downloads instead of hammering it with the whole
        // window up front (which gets rate limited and fails).
        if (pacedAny && job.delaySeconds > 0) delay(job.delaySeconds * 1_000L)
        pacedAny = true
        // the player owns the connection while it buffers; a stuck player
        // must not stall the cache forever, hence the bounded wait
        val _ = withTimeoutOrNull(PLAYBACK_GATE_WAIT_MS) {
          playbackIoGate.whilePlaybackLoads { }
        }
        // resolveStreamUrl reports failures as null and only throws on
        // cancellation, so no runCatching: it would swallow the cancellation
        val audio = catalog.resolveStreamUrl(ref)
        if (audio == null || audio.url.isBlank()) {
          failed++
          consecutiveFailures++
          updateState(bookUri, generation) { it.copy(done = done, failed = failed) }
          if (consecutiveFailures >= ABORT_AFTER_CONSECUTIVE_FAILURES) break
          continue
        }
        val downloaded = downloadSlots.withPermit { downloadToCache(ref, audio) }
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
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      // an unexpected failure must not leave the dialog spinning forever and
      // must not vanish without a trace: the job stays persisted, so the next
      // start retries it
      Logger.w(e, "Online cache job of $bookUri failed")
    } finally {
      // a cancelled job must not leave the dialog spinning: chapters cached
      // so far are kept and the job simply reports as finished
      updateState(bookUri, generation) {
        it.copy(downloading = false, currentTitle = "", awaitingConfirmation = false)
      }
    }
    // an unfinished job stays persisted so the next app start continues it,
    // and so does one whose chapters failed: the missing ones are the work of
    // the next run. `done` counts every window chapter that is on the device,
    // the failed ones included in its shortfall
    persistUnlessGivenUp(
      bookUri = bookUri,
      generation = generation,
      job = job,
      progressed = done > doneAtStart,
      unfinished = done < window.size && !declined,
    )
    if (declined) clearState(bookUri, generation)
  }

  /**
   * Keeps the persisted request while it still has chapters to cache and while
   * it keeps making progress. A run that downloaded something resets the
   * counter, so a slow cache is never given up on; a job whose runs end
   * without a single chapter (dead source, no network) is dropped instead of
   * being retried on every app start.
   */
  private fun persistUnlessGivenUp(
    bookUri: String,
    generation: Int,
    job: OnlineCacheJob,
    progressed: Boolean,
    unfinished: Boolean = true,
  ) {
    // a stale job must not delete the persisted request of its successor
    if (!isCurrent(bookUri, generation)) return
    val attempts = if (progressed) 0 else job.attempts + 1
    if (unfinished && attempts < MAX_RESUME_ATTEMPTS) {
      remember(job.copy(attempts = attempts))
    } else {
      forget(bookUri)
    }
  }

  /** True while [generation] is the job of [bookUri] that owns its outcome. */
  private fun isCurrent(
    bookUri: String,
    generation: Int,
  ): Boolean {
    return generations[bookUri] == generation
  }

  /**
   * Publishes the metered question and suspends until the user answered it.
   * Prompts are serialized, so two jobs never stack two dialogs; a cancelled
   * job abandons the wait and hands the prompt slot to the next job.
   */
  private suspend fun awaitMeteredPermission(
    confirmation: OnlineCacheConfirmation,
    bookUri: String,
    generation: Int,
  ): Boolean {
    return confirmationGate.withLock {
      val answer = CompletableDeferred<Boolean>()
      pendingConfirmation.set(answer)
      _meteredConfirmation.value = confirmation
      updateState(bookUri, generation) { it.copy(awaitingConfirmation = true) }
      try {
        answer.await()
      } finally {
        pendingConfirmation.set(null)
        _meteredConfirmation.value = null
        updateState(bookUri, generation) { it.copy(awaitingConfirmation = false) }
      }
    }
  }

  /**
   * The chapter the book is in right now is the first one cached: going
   * offline mid-chapter must keep playing.
   */
  private fun startIndex(
    book: OnlineBook?,
    chapters: List<OnlineChapter>,
  ): Int {
    return book?.currentChapterId
      ?.takeIf { id -> id.isNotBlank() }
      ?.let { id -> chapters.indexOfFirst { it.id == id } }
      ?.takeIf { it >= 0 } ?: 0
  }

  private fun chapterRef(
    bookRef: OnlineBookRef,
    chapter: OnlineChapter,
  ): OnlineChapterRef {
    return OnlineChapterRef(bookRef.source, bookRef.bookId, chapter.id)
  }

  /** Remembers the request before it runs, so a killed process resumes it. */
  private fun remember(job: OnlineCacheJob) {
    scope.launch {
      // a store hiccup must not take the cache job down with it
      val _ = runCatching {
        jobsStore.updateData { jobs -> jobs.filterNot { it.bookUri == job.bookUri } + job }
      }
    }
  }

  /** Drops the persisted request: the job is done, cancelled or declined. */
  private fun forget(bookUri: String) {
    scope.launch {
      val _ = runCatching { jobsStore.updateData { jobs -> jobs.filterNot { it.bookUri == bookUri } } }
    }
  }

  /** Downloads [audio] into the file cache; false when it could not be stored. */
  private suspend fun downloadToCache(
    ref: OnlineChapterRef,
    audio: OnlineAudio,
  ): Boolean = withContext(Dispatchers.IO) {
    try {
      val requestUrl = OnlineStreamUrlPolicy.applyCertFallback(audio.url)
      download(requestUrl, audio.headers).use { response ->
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
  private suspend fun download(url: String, headers: Map<String, String>): Response {
    return try {
      executeCancellable(downloadRequest(url, headers))
    } catch (e: IOException) {
      coroutineContext.ensureActive()
      val httpUrl = OnlineStreamUrlPolicy.downgradeToHttp(url)
      if (httpUrl == null || !isCertificateProblem(e)) throw e
      Logger.w("TLS handshake failed for source host, retrying over http: ${e.message}")
      OnlineStreamUrlPolicy.rememberCertBroken(url)
      executeCancellable(downloadRequest(httpUrl, headers))
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

  private suspend fun downloadRequest(url: String, headers: Map<String, String>): Request {
    val builder = Request.Builder().url(url)
    // plugin sources can require their own request headers (referer, user
    // agent, cookies); they take precedence over the generic auth logic
    for ((name, value) in headers) {
      if (name.isNotBlank() && value.isNotBlank()) {
        builder.header(name, value)
      }
    }
    // urls served by the configured server require the bearer token; third
    // party cdn links must not receive it
    val base = baseUrlStore.data.first().trim().trimEnd('/')
    val token = tokenStore.data.first().trim()
    if (token.isNotEmpty() && base.isNotEmpty() && url.startsWith(base) &&
      headers.none { it.key.equals("Authorization", ignoreCase = true) }
    ) {
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

    /** Upper bound of the per-episode delay (5 minutes): a typo must not stall a cache for hours. */
    const val MAX_EPISODE_DELAY_SECONDS = 300

    /** A dead network fails fast instead of grinding through the whole window. */
    const val ABORT_AFTER_CONSECUTIVE_FAILURES = 5

    /**
     * How many runs of the same job may end without downloading a single
     * chapter before the job is dropped. A crash or a kill is not counted, a
     * run that downloaded something resets the counter.
     */
    const val MAX_RESUME_ATTEMPTS = 3

    /** How long a download waits for the player to finish buffering. */
    const val PLAYBACK_GATE_WAIT_MS = 15_000L
  }
}
