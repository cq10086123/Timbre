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
import kotlinx.coroutines.channels.Channel
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
import okhttp3.MediaType
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

/** State of one chapter of the cached window, drawn as the progress map. */
public enum class OnlineCacheMark {
  /** Not reached yet. */
  PENDING,

  /** The chapter in flight. */
  RUNNING,

  /** A complete file is on the device. */
  CACHED,

  /** The chapter was attempted and failed. */
  FAILED,
}

/** Why one chapter could not be cached. */
public enum class OnlineCacheFailureReason {
  /** The source handed out no playable url for it. */
  RESOLVE,

  /** The transfer broke off (network error, timeout, rejected request). */
  DOWNLOAD,

  /** The server answered with a web page or json instead of audio. */
  NOT_AUDIO,

  /** The transfer ended before the announced length arrived. */
  INCOMPLETE,
}

/** One failed chapter, with the reason and what the source actually said. */
public data class OnlineCacheFailure(
  val title: String,
  val reason: OnlineCacheFailureReason,
  /** 1-based chapter number in the book; 0 when the source did not number it. */
  val chapterOrder: Int = 0,
  /** Offset inside the cached window, so the progress map can find it again. */
  val index: Int = 0,
  /**
   * The raw text the source or the server answered with (a business message, an
   * exception, an http status), null when it said nothing. Shown verbatim: a
   * generic "resolve failed" is what made a broken source undiagnosable.
   */
  val detail: String? = null,
)

/** Why a cache run ended, so the dialog can say more than "it stopped". */
public enum class OnlineCacheStopReason {
  /** Every chapter of the window is on the device. */
  COMPLETED,

  /** Too many chapters failed in a row; the source is probably rate limiting. */
  TOO_MANY_FAILURES,

  /** The chapter list could not be loaded at all. */
  NO_CHAPTER_LIST,

  /** The user refused to cache over mobile data. */
  METERED_DECLINED,

  /** Several runs in a row ended without a single chapter. */
  GAVE_UP,

  /** Something unexpected threw. */
  ERROR,
}

/** Progress of one manual cache job; [states] keys it by the canonical book uri. */
public data class OnlineCacheState(
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
  /** Bytes already written for the chapter in flight. */
  val currentBytes: Long = 0L,
  /** Size of the chapter in flight; -1 while the server has not announced it. */
  val currentTotalBytes: Long = -1L,
  /** Smoothed transfer rate of the chapter in flight, 0 while unknown. */
  val bytesPerSecond: Long = 0L,
  /**
   * One mark per chapter of the window, in order. Capped at [MAX_MARKS] so a
   * ten thousand chapter job does not copy a list that size on every tick.
   */
  val marks: List<OnlineCacheMark> = emptyList(),
  /** The chapters that failed, capped at [MAX_LISTED_FAILURES]. */
  val failures: List<OnlineCacheFailure> = emptyList(),
  /** Set once the run is over, so the dialog can explain why it stopped. */
  val stopReason: OnlineCacheStopReason? = null,
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
 * - resolving a chapter reuses [OnlinePlaybackCatalog.resolveStreamDetail], whose
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
 * Every run ends in a state that names its outcome ([OnlineCacheStopReason])
 * and, when chapters failed, the reason per chapter: a cache that silently
 * stops is a cache the user cannot act on.
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
  private val chapterStore: OnlineChapterStore,
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

  /**
   * Store writes run on one consumer in send order. Two `launch`es would race:
   * a `forget` from a cancel landing before the `remember` of the very job it
   * cancels leaves a request that comes back on the next app start.
   */
  private val storeWrites = Channel<suspend () -> Unit>(Channel.UNLIMITED)

  private val _states = MutableStateFlow<Map<String, OnlineCacheState>>(emptyMap())

  /** The cache jobs by canonical book uri. */
  public val states: StateFlow<Map<String, OnlineCacheState>> = _states

  private val _meteredConfirmation = MutableStateFlow<OnlineCacheConfirmation?>(null)

  /** The metered question the UI has to answer, or null when there is none. */
  public val meteredConfirmation: StateFlow<OnlineCacheConfirmation?> = _meteredConfirmation

  init {
    scope.launch {
      for (write in storeWrites) {
        try {
          write()
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          // a store hiccup must not take the cache job down with it
          Logger.w(e, "Online cache job store write failed")
        }
      }
    }
  }

  /** The cache job of [bookUri], or null when it was never cached this session. */
  public fun stateFor(bookUri: String): Flow<OnlineCacheState?> {
    return states.map { it[bookUri] }
  }

  /** Describes what is cached of [bookId] for the cache dialog. */
  public suspend fun cachedInfo(bookId: BookId): OnlineCachedInfo? {
    val bookRef = OnlineUri.parseBookUri(bookId.value) ?: return null
    val book = catalog.lookupOnlineBook(bookRef.source, bookRef.bookId)
    val chapters = book?.chapters?.takeIf { it.isNotEmpty() }
      ?: runCatching { chapterStore.chapters(bookRef.key) }.getOrDefault(emptyList()).takeIf { it.isNotEmpty() }
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
    val job = jobs[bookId.value]
    job?.cancel()
    // a cancelled download leaves the read loop at once, but a call still
    // waiting for the response headers only aborts when its socket times out.
    // Waiting for that would freeze the clear button for up to a minute, so
    // the wait is bounded - a download that outlives it sees a stale
    // generation and discards its file instead of publishing it into the
    // directory that was just deleted.
    val _ = withTimeoutOrNull(CLEAR_WAIT_MS) { job?.join() }
    forget(bookId.value)
    return withContext(dispatcherProvider.io) {
      val cleared = fileCache.clearBook(bookRef.source, bookRef.bookId)
      _states.update { it - bookId.value }
      cleared
    }
  }

  /**
   * Writes only when [generation] is still the latest job of [bookUri].
   *
   * The check sits *inside* the atomic update on purpose: `cancel` bumps the
   * generation and then drops the entry, so a progress tick that read the
   * generation just before the bump could otherwise write its state back
   * after the drop and leave a frozen bar that never moves again. Re-running
   * the check after a CAS retry closes that window.
   */
  private fun setState(
    bookUri: String,
    generation: Int,
    state: OnlineCacheState,
  ) {
    _states.update { current ->
      if (!isCurrent(bookUri, generation)) return@update current
      current + (bookUri to state)
    }
  }

  /** Updates only when [generation] is still the latest job of [bookUri]. */
  private fun updateState(
    bookUri: String,
    generation: Int,
    transform: (OnlineCacheState) -> OnlineCacheState,
  ) {
    _states.update { current ->
      if (!isCurrent(bookUri, generation)) return@update current
      val state = current[bookUri] ?: return@update current
      current + (bookUri to transform(state))
    }
  }

  /**
   * Ends the visible job of [bookUri] with a reason the dialog can explain.
   * The entry stays, so the sheet keeps showing what happened instead of
   * silently reverting to "nothing is running".
   */
  private fun stopState(
    bookUri: String,
    generation: Int,
    reason: OnlineCacheStopReason,
  ) {
    updateState(bookUri, generation) { it.ended(reason) }
  }

  /**
   * The fields of a run that is over: no chapter in flight, no speed, no
   * "downloading". Shared by every path that ends a job so a new terminal
   * field can never be forgotten in one of them.
   */
  private fun OnlineCacheState.ended(reason: OnlineCacheStopReason?): OnlineCacheState {
    return copy(
      downloading = false,
      awaitingConfirmation = false,
      currentTitle = "",
      currentBytes = 0,
      currentTotalBytes = -1,
      bytesPerSecond = 0,
      stopReason = reason,
    )
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
    // must not send a single request. The chapter list file counts as local
    // too; only a failing store read falls back to the source below instead
    // of killing the job without a word
    val localBook = runCatching { catalog.lookupOnlineBook(bookRef.source, bookRef.bookId) }.getOrNull()
    val localChapters = localBook?.chapters?.takeIf { it.isNotEmpty() }
      ?: runCatching { chapterStore.chapters(bookRef.key) }.getOrDefault(emptyList())
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
        // the user said no: nothing was downloaded, so the job is dropped and
        // not resumed - the sheet still says why it did nothing
        stopState(bookUri, generation, OnlineCacheStopReason.METERED_DECLINED)
        forget(bookUri)
        return
      }
    }
    val chapters = localChapters.takeIf { it.isNotEmpty() }
      ?: runCatching { service.chapters(bookRef.source, bookRef.bookId) }.getOrNull().orEmpty()
    if (chapters.isEmpty()) {
      // no chapter list at all (offline, source gone): keep the job so the
      // next start can retry, unless it keeps failing without any progress
      stopState(bookUri, generation, OnlineCacheStopReason.NO_CHAPTER_LIST)
      val _ = persistUnlessGivenUp(bookUri, generation, job, progressed = false)
      return
    }
    val startOffset = startIndex(localBook, chapters)
    val window = chapters.drop(startOffset).take(job.count)
    val marks = MutableList(minOf(window.size, MAX_MARKS)) { OnlineCacheMark.PENDING }
    var done = 0
    window.forEachIndexed { index, chapter ->
      if (fileCache.isCached(chapterRef(bookRef, chapter))) {
        done++
        if (index < marks.size) marks[index] = OnlineCacheMark.CACHED
      }
    }
    val doneAtStart = done
    var failed = 0
    var consecutiveFailures = 0
    var pacedAny = false
    var declined = false
    var stopReason: OnlineCacheStopReason? = null
    val failures = mutableListOf<OnlineCacheFailure>()

    // the state is rewritten from several threads: the download callback
    // publishes progress while the loop is suspended in the transfer
    val stateLock = Any()
    var current = OnlineCacheState(
      total = window.size,
      done = done,
      failed = 0,
      downloading = true,
      marks = marks.toList(),
    )
    setState(bookUri, generation, current)

    fun publish(transform: (OnlineCacheState) -> OnlineCacheState) {
      val next = synchronized(stateLock) {
        current = transform(current)
        current
      }
      setState(bookUri, generation, next)
    }

    fun mark(
      index: Int,
      value: OnlineCacheMark,
    ) {
      if (index < 0 || index >= marks.size || marks[index] == value) return
      marks[index] = value
      publish { it.copy(marks = marks.toList()) }
    }

    fun fail(
      chapter: OnlineChapter,
      reason: OnlineCacheFailureReason,
      detail: String?,
      index: Int,
    ) {
      failed++
      consecutiveFailures++
      if (failures.size < MAX_LISTED_FAILURES) {
        failures.add(
          OnlineCacheFailure(
            title = chapter.title,
            reason = reason,
            chapterOrder = chapter.order,
            index = index,
            detail = detail?.take(MAX_FAILURE_DETAIL_CHARS),
          ),
        )
      }
      mark(index, OnlineCacheMark.FAILED)
      publish { it.copy(done = done, failed = failed, failures = failures.toList()) }
    }

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
        publish {
          it.copy(
            currentTitle = chapter.title,
            currentBytes = 0,
            currentTotalBytes = -1,
            bytesPerSecond = 0,
          )
        }
        mark(offset, OnlineCacheMark.RUNNING)
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
        // resolving must stay silent here: a cache run failing to resolve a
        // chapter is not the player failing to play one, and the error flow
        // would pop the playback dialog over a background download. The
        // reason is kept in the failure instead, so the sheet can show what
        // the source actually said.
        val resolution = catalog.resolveStreamDetail(ref)
        val stream = (resolution as? StreamResolution.Success)?.stream
        if (stream == null || stream.url.isBlank()) {
          fail(
            chapter,
            OnlineCacheFailureReason.RESOLVE,
            (resolution as? StreamResolution.Failure)?.detail,
            offset,
          )
          if (consecutiveFailures >= ABORT_AFTER_CONSECUTIVE_FAILURES) break
          continue
        }
        val sampler = SpeedSampler()
        val outcome = downloadSlots.withPermit {
          downloadToCache(
            ref = ref,
            stream = stream,
            stillWanted = { isCurrent(bookUri, generation) },
          ) { written, total ->
            val speed = sampler.sample(written)
            publish {
              it.copy(
                currentBytes = written,
                currentTotalBytes = total,
                bytesPerSecond = speed,
              )
            }
          }
        }
        when (outcome) {
          DownloadOutcome.Stored -> {
            done++
            consecutiveFailures = 0
            mark(offset, OnlineCacheMark.CACHED)
            publish { it.copy(done = done) }
          }
          is DownloadOutcome.Failed -> fail(chapter, outcome.reason, outcome.detail, offset)
        }
        if (consecutiveFailures >= ABORT_AFTER_CONSECUTIVE_FAILURES) break
      }
      stopReason = when {
        declined -> OnlineCacheStopReason.METERED_DECLINED
        done >= window.size -> OnlineCacheStopReason.COMPLETED
        consecutiveFailures >= ABORT_AFTER_CONSECUTIVE_FAILURES -> OnlineCacheStopReason.TOO_MANY_FAILURES
        else -> null
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      // an unexpected failure must not leave the dialog spinning forever and
      // must not vanish without a trace: the job stays persisted, so the next
      // start retries it
      Logger.w(e, "Online cache job of $bookUri failed")
      stopReason = OnlineCacheStopReason.ERROR
    } finally {
      // a cancelled job must not leave the dialog spinning: chapters cached
      // so far are kept and the job simply reports as finished
      val reason = stopReason
      publish { it.ended(reason) }
    }
    // an unfinished job stays persisted so the next app start continues it,
    // and so does one whose chapters failed: the missing ones are the work of
    // the next run. `done` counts every window chapter that is on the device,
    // the failed ones included in its shortfall
    val gaveUp = persistUnlessGivenUp(
      bookUri = bookUri,
      generation = generation,
      job = job,
      progressed = done > doneAtStart,
      unfinished = done < window.size && !declined,
    )
    if (gaveUp) {
      updateState(bookUri, generation) { it.copy(stopReason = OnlineCacheStopReason.GAVE_UP) }
    }
  }

  /**
   * Keeps the persisted request while it still has chapters to cache and while
   * it keeps making progress. A run that downloaded something resets the
   * counter, so a slow cache is never given up on; a job whose runs end
   * without a single chapter (dead source, no network) is dropped instead of
   * being retried on every app start.
   *
   * Returns true when the job was dropped because it kept ending without
   * progress, so the caller can say so instead of leaving a stale bar.
   */
  private fun persistUnlessGivenUp(
    bookUri: String,
    generation: Int,
    job: OnlineCacheJob,
    progressed: Boolean,
    unfinished: Boolean = true,
  ): Boolean {
    // a stale job must not delete the persisted request of its successor
    if (!isCurrent(bookUri, generation)) return false
    val attempts = if (progressed) 0 else job.attempts + 1
    if (unfinished && attempts < MAX_RESUME_ATTEMPTS) {
      remember(job.copy(attempts = attempts))
      return false
    }
    forget(bookUri)
    return unfinished
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
   * Prompts are serialized, so two jobs never stack two dialogs; a job that
   * queues behind another one already says it is waiting instead of looking
   * frozen at zero.
   */
  private suspend fun awaitMeteredPermission(
    confirmation: OnlineCacheConfirmation,
    bookUri: String,
    generation: Int,
  ): Boolean {
    // drop the chapter in flight: while the question is open nothing is being
    // downloaded, and "downloading: episode 7" with a frozen bar would lie
    updateState(bookUri, generation) {
      it.copy(
        awaitingConfirmation = true,
        currentTitle = "",
        currentBytes = 0,
        currentTotalBytes = -1,
        bytesPerSecond = 0,
      )
    }
    try {
      return confirmationGate.withLock {
        val answer = CompletableDeferred<Boolean>()
        pendingConfirmation.set(answer)
        _meteredConfirmation.value = confirmation
        try {
          answer.await()
        } finally {
          pendingConfirmation.set(null)
          _meteredConfirmation.value = null
        }
      }
    } finally {
      updateState(bookUri, generation) { it.copy(awaitingConfirmation = false) }
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
    storeWrites.trySend {
      jobsStore.updateData { jobs -> jobs.filterNot { it.bookUri == job.bookUri } + job }
    }
  }

  /** Drops the persisted request: the job is done, cancelled or declined. */
  private fun forget(bookUri: String) {
    storeWrites.trySend {
      jobsStore.updateData { jobs -> jobs.filterNot { it.bookUri == bookUri } }
    }
  }

  /** The result of one chapter download, with the reason when it failed. */
  private sealed interface DownloadOutcome {
    /** The chapter is on the device. */
    data object Stored : DownloadOutcome

    /** [detail] is the raw answer (http status, content type, body head, bytes). */
    data class Failed(
      val reason: OnlineCacheFailureReason,
      val detail: String? = null,
    ) : DownloadOutcome
  }

  /**
   * Downloads [stream] into the file cache, reporting the transfer so the
   * dialog can show a per chapter bar.
   *
   * A 200 is not proof of a chapter: sources keep serving html error pages and
   * links that expire into a json error, and a truncated transfer looks like a
   * finished one. Storing those would make [OnlineChapterFileCache.isCached]
   * skip the chapter forever, so the file is only published when its bytes
   * match what the server announced and it does not start like a web page.
   */
  private suspend fun downloadToCache(
    ref: OnlineChapterRef,
    stream: ResolvedStream,
    stillWanted: () -> Boolean,
    onProgress: (written: Long, total: Long) -> Unit,
  ): DownloadOutcome = withContext(Dispatchers.IO) {
    try {
      val requestUrl = OnlineStreamUrlPolicy.applyCertFallback(stream.url)
      download(requestUrl, stream.headers).use { response ->
        if (!response.isSuccessful) {
          Logger.w("Online chapter download for $ref answered ${response.code}")
          return@withContext DownloadOutcome.Failed(
            OnlineCacheFailureReason.DOWNLOAD,
            "HTTP ${response.code} ${response.message}".trim(),
          )
        }
        if (isErrorContentType(response.body.contentType())) {
          Logger.w("Online chapter download for $ref answered ${response.body.contentType()} instead of audio")
          return@withContext DownloadOutcome.Failed(
            OnlineCacheFailureReason.NOT_AUDIO,
            "Content-Type: ${response.body.contentType()}",
          )
        }
        val expected = response.body.contentLength()
        val tmp = fileCache.tmpFileFor(ref)
        tmp.parentFile?.mkdirs()
        var written = 0L
        var rejected = false
        var rejectedHead = ""
        var lastEmit = 0L
        response.body.byteStream().use { input ->
          FileOutputStream(tmp).use { output ->
            val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
            while (true) {
              coroutineContext.ensureActive()
              val read = input.read(buffer)
              if (read < 0) break
              // an expired link often answers 200 with "{" or "<!doctype":
              // checking the first chunk keeps that out of the cache
              if (written == 0L && looksLikeText(buffer, read)) {
                rejected = true
                // a cut in the middle of a UTF-8 character would end the preview
                // with a replacement char; the tail is not worth showing anyway
                rejectedHead = String(buffer, 0, minOf(read, BODY_PREVIEW_BYTES), Charsets.UTF_8)
                  .trimEnd('\uFFFD')
                break
              }
              output.write(buffer, 0, read)
              written += read
              val now = System.currentTimeMillis()
              if (now - lastEmit >= PROGRESS_INTERVAL_MS) {
                lastEmit = now
                onProgress(written, expected)
              }
            }
          }
        }
        if (rejected) {
          tmp.delete()
          return@withContext DownloadOutcome.Failed(
            OnlineCacheFailureReason.NOT_AUDIO,
            rejectedHead.trim().ifEmpty { "Content-Type: ${response.body.contentType()}" },
          )
        }
        onProgress(written, expected)
        if (written <= 0L) {
          tmp.delete()
          return@withContext DownloadOutcome.Failed(
            OnlineCacheFailureReason.DOWNLOAD,
            "HTTP ${response.code} ${response.message} · 0 bytes".trim(),
          )
        }
        if (expected > 0L && written != expected) {
          Logger.w("Online chapter download for $ref stopped at $written of $expected bytes")
          tmp.delete()
          return@withContext DownloadOutcome.Failed(
            OnlineCacheFailureReason.INCOMPLETE,
            "$written / $expected bytes",
          )
        }
        // a clear (or a newer job) may have raced this download: publishing
        // now would put a chapter back into a directory that was just emptied
        if (!stillWanted()) {
          tmp.delete()
          return@withContext DownloadOutcome.Failed(OnlineCacheFailureReason.DOWNLOAD)
        }
        coroutineContext.ensureActive()
        if (fileCache.completeDownload(ref)) {
          DownloadOutcome.Stored
        } else {
          DownloadOutcome.Failed(OnlineCacheFailureReason.DOWNLOAD)
        }
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
      DownloadOutcome.Failed(OnlineCacheFailureReason.DOWNLOAD, e.toString())
    }
  }

  /**
   * A content type that says "this is a web page / api answer", never audio.
   * Unknown types stay allowed: plenty of cdns serve mp3 as
   * `application/octet-stream`, and rejecting those would break working
   * sources.
   */
  private fun isErrorContentType(contentType: MediaType?): Boolean {
    if (contentType == null) return false
    val type = contentType.type.lowercase()
    val subtype = contentType.subtype.lowercase()
    return type == "text" || "html" in subtype || "json" in subtype || "xml" in subtype
  }

  /** True when the first bytes of a body look like markup instead of audio. */
  private fun looksLikeText(
    buffer: ByteArray,
    length: Int,
  ): Boolean {
    for (index in 0 until minOf(length, TEXT_SNIFF_BYTES)) {
      val byte = buffer[index].toInt() and 0xFF
      when {
        // a utf-8 bom or leading whitespace is still html
        byte == 0xEF || byte == 0xBB || byte == 0xBF -> continue
        byte == 0x20 || byte == 0x09 || byte == 0x0A || byte == 0x0D -> continue
        byte == '<'.code || byte == '{'.code || byte == '['.code -> return true
        else -> return false
      }
    }
    return false
  }

  /**
   * Runs one download request, healing hosts whose TLS certificate is
   * unusable the same way streaming does: after a failed https handshake the
   * address is retried over plain http.
   */
  private suspend fun download(
    url: String,
    headers: Map<String, String>,
  ): Response {
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

  private suspend fun downloadRequest(
    url: String,
    sourceHeaders: Map<String, String>,
  ): Request {
    val builder = Request.Builder().url(url)
    // urls served by the configured server require the bearer token; third
    // party cdn links must not receive it
    val base = baseUrlStore.data.first().trim().trimEnd('/')
    val token = tokenStore.data.first().trim()
    if (token.isNotEmpty() && base.isNotEmpty() && url.startsWith(base)) {
      builder.header("Authorization", "Bearer $token")
    }
    // headers the source script asked for (Referer / User-Agent / Cookie for
    // hotlink protected cdns). Playback sends them too; without them a
    // chapter downloads fine while streaming and fails every single time.
    for ((name, value) in sourceHeaders) {
      builder.header(name, value)
    }
    return builder.build()
  }

  private fun isCertificateProblem(e: Throwable): Boolean = generateSequence(e) { it.cause }
    .take(8)
    .any { it is javax.net.ssl.SSLException || it is java.security.cert.CertificateException }

  /**
   * Smooths the transfer rate of one chapter: raw samples jump around, a
   * smoothed number is what tells the user whether the source is slow or the
   * file is just big.
   */
  private class SpeedSampler {
    private var lastBytes = 0L
    private var lastAt = 0L
    private var rate = 0L

    fun sample(bytes: Long): Long {
      val now = System.currentTimeMillis()
      if (lastAt == 0L) {
        lastBytes = bytes
        lastAt = now
        return 0L
      }
      val elapsed = now - lastAt
      if (elapsed < SPEED_SAMPLE_MS) return rate
      val delta = bytes - lastBytes
      lastAt = now
      lastBytes = bytes
      if (delta <= 0L) return rate
      val instant = delta * 1_000L / elapsed
      rate = if (rate == 0L) instant else (rate * 3L + instant) / 4L
      return rate
    }
  }

  public companion object {
    /** Upper bound of one manual cache job; the window is capped by the book anyway. */
    private const val MAX_MANUAL_CACHE = 10_000

    /**
     * Upper bound of the per-episode delay (5 minutes): a typo must not stall a
     * cache for hours. Public because the cache sheet clamps its input with the
     * same number instead of inventing a second limit.
     */
    public const val MAX_EPISODE_DELAY_SECONDS: Int = 300

    /** A dead network fails fast instead of grinding through the whole window. */
    private const val ABORT_AFTER_CONSECUTIVE_FAILURES = 5

    /**
     * How many runs of the same job may end without downloading a single
     * chapter before the job is dropped. A crash or a kill is not counted, a
     * run that downloaded something resets the counter.
     */
    private const val MAX_RESUME_ATTEMPTS = 3

    /** How long a download waits for the player to finish buffering. */
    private const val PLAYBACK_GATE_WAIT_MS = 15_000L

    /** How long a clear waits for the stopped job before deleting anyway. */
    private const val CLEAR_WAIT_MS = 2_000L

    /** The progress map covers the first chapters of the window only. */
    private const val MAX_MARKS = 240

    /** The failure list is a diagnosis, not a log: a dozen is plenty. */
    private const val MAX_LISTED_FAILURES = 12

    /** Progress callbacks are throttled: five states a second is smooth. */
    private const val PROGRESS_INTERVAL_MS = 200L

    /** Minimum window between two speed samples. */
    private const val SPEED_SAMPLE_MS = 700L

    private const val DOWNLOAD_BUFFER_BYTES = 64 * 1024

    private const val TEXT_SNIFF_BYTES = 8

    /** How much of a wrong answer (html/json page) is kept as the reason. */
    private const val BODY_PREVIEW_BYTES = 240

    /** A failure reason is a diagnosis, not a log dump; keep it readable. */
    private const val MAX_FAILURE_DETAIL_CHARS = 400
  }
}
