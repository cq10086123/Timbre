package voice.core.playback.player

import androidx.datastore.core.DataStore
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import voice.core.common.DispatcherProvider
import voice.core.logging.api.Logger
import voice.core.online.OnlineChapterRef
import voice.core.online.OnlinePlaybackCatalog
import voice.core.online.OnlinePreloadSettings
import voice.core.online.OnlinePreloadSettingsStore
import voice.core.online.OnlineSourceStreamingClient
import voice.core.online.OnlineStreamHeadProbe
import voice.core.online.OnlineUri
import voice.core.playback.session.MediaId
import voice.core.playback.session.toMediaIdOrNull
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration.Companion.seconds

/**
 * The single place an online book spends source api calls ahead of the chapter
 * the user actually listens to. Replaces the three request paths this used to
 * have (an unconditional current chapter warm-up on entering the player, an
 * ahead prefetch of two chapters, and a five chapter duration probe) - rate
 * limited sources could not survive their combined traffic.
 *
 * The rules, configurable through [OnlinePreloadSettings]:
 * - nothing loads before playback: entering the player spends zero requests,
 * - the current chapter starts its preload chain only after it played
 *   [OnlinePreloadSettings.triggerSeconds], and only when the chapter itself
 *   is at least that long,
 * - a chapter switch / pause cancels the running chain: nothing carries over,
 *   the next chapter starts its own chain from zero,
 * - at most [OnlinePreloadSettings.chapterCount] chapters after the current
 *   one hold resolved urls; the chain tops up the missing ones with a pause
 *   of [OnlinePreloadSettings.intervalSeconds] between each,
 * - every resolve doubles as a duration measurement (a head fetch against the
 *   audio host, not the api), so the chapter list turns exact ahead of play.
 *
 * Local and WebDav books never enter this class: their uris are not online
 * uris and the listener ignores them.
 */
internal class OnlinePlaybackPreloader(
  private val player: Player,
  private val catalog: OnlinePlaybackCatalog,
  @OnlinePreloadSettingsStore
  private val settingsStore: DataStore<OnlinePreloadSettings>,
  @OnlineSourceStreamingClient
  private val httpClient: OkHttpClient,
  private val scope: CoroutineScope,
  private val dispatcherProvider: DispatcherProvider,
) : Player.Listener {

  private var tickJob: Job? = null
  private var chainJob: Job? = null

  override fun onMediaItemTransition(
    mediaItem: MediaItem?,
    reason: Int,
  ) {
    restart()
  }

  override fun onPlaybackStateChanged(playbackState: Int) {
    restart()
  }

  override fun onPlayWhenReadyChanged(
    playWhenReady: Boolean,
    reason: Int,
  ) {
    restart()
  }

  /**
   * One tick per second while a chapter plays. Every restart (chapter switch,
   * pause, book change) tears the old tick and chain down first, so nothing
   * ever survives across chapters. Everything here stays on the player's main
   * thread: media3 throws on player accesses from any other thread, and only
   * the blocking head probe hops to io.
   */
  private fun restart() {
    chainJob?.cancel()
    chainJob = null
    tickJob?.cancel()
    tickJob = null
    if (currentRef() == null || !player.playWhenReady || player.playbackState != Player.STATE_READY) {
      return
    }
    tickJob = scope.launch {
      try {
        tickLoop()
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Logger.w("Online preload tick failed: $e")
      }
    }
  }

  private suspend fun tickLoop() {
    while (coroutineContext.isActive) {
      delay(1.seconds)
      val ref = currentRef() ?: return
      if (!player.playWhenReady || player.playbackState != Player.STATE_READY) return
      val settings = readSettings()
      applyCacheTtl(settings)
      val playedMs = player.currentPosition.takeUnless { it == C.TIME_UNSET } ?: 0L
      val durationMs = player.duration.takeUnless { it == C.TIME_UNSET }?.takeIf { it > 0 } ?: 0L
      val cachedAhead = catalog.cachedChaptersAhead(ref, settings.chapterCount)
      val should = PreloadPolicy.shouldStartNextPreload(
        PreloadPolicy.Input(
          settings = settings,
          playing = true,
          playedMs = playedMs,
          chapterDurationMs = durationMs,
          preloadedAheadCount = cachedAhead,
          chainRunning = chainJob?.isActive == true,
        ),
      )
      if (!should) continue
      chainJob = scope.launch {
        try {
          preloadChain(ref)
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          Logger.w("Online preload chain failed: $e")
        }
      }
    }
  }

  /**
   * Resolves the next uncached chapters one by one. Every turn re-checks the
   * rules: the moment the chapter changed (or playback stopped) the chain
   * belongs to the past and returns - the new chapter starts fresh.
   */
  private suspend fun preloadChain(startRef: OnlineChapterRef) {
    val book = catalog.lookupOnlineBook(startRef.source, startRef.bookId) ?: return
    val chapters = book.chapters
    val index = chapters.indexOfFirst { it.id == startRef.chapterId }
    if (index < 0) return
    while (coroutineContext.isActive) {
      val current = currentRef() ?: return
      if (current.source != startRef.source ||
        current.bookId != startRef.bookId ||
        current.chapterId != startRef.chapterId
      ) {
        return
      }
      if (!player.playWhenReady || player.playbackState != Player.STATE_READY) return
      val settings = readSettings()
      val cachedAhead = catalog.cachedChaptersAhead(startRef, settings.chapterCount)
      if (cachedAhead >= settings.chapterCount) return
      val chapter = chapters.getOrNull(index + 1 + cachedAhead) ?: return
      val nextRef = OnlineChapterRef(startRef.source, startRef.bookId, chapter.id)
      val stream = runCatching {
        catalog.resolveStreamUrl(nextRef, reportErrors = false)
      }.getOrNull()
      if (stream != null && catalog.measuredDurationMs(nextRef.source, nextRef.bookId, nextRef.chapterId) == null) {
        // the head fetch hits the audio host, not the source api: it does not
        // touch the rate limit and turns the placeholder duration exact. It is
        // a blocking call, so it hops to io away from the player thread.
        val durationMs = withContext(dispatcherProvider.io) {
          runCatching {
            OnlineStreamHeadProbe.probeDurationMs(stream.url, stream.headers, httpClient)
          }.getOrNull()
        }
        if (durationMs != null && durationMs > 0L) {
          catalog.recordMeasuredDuration(nextRef.source, nextRef.bookId, nextRef.chapterId, durationMs)
        }
      }
      delay(settings.intervalSeconds.seconds)
    }
  }

  private suspend fun readSettings(): OnlinePreloadSettings {
    return OnlinePreloadSettings.coerce(settingsStore.data.first())
  }

  /**
   * A preload chain of [OnlinePreloadSettings.chapterCount] chapters with
   * [OnlinePreloadSettings.intervalSeconds] between each can easily outrun
   * the default url ttl: widen it so the last preloaded chapter is still
   * alive when playback reaches it.
   */
  private fun applyCacheTtl(settings: OnlinePreloadSettings) {
    val chainMs = (settings.chapterCount * settings.intervalSeconds + settings.triggerSeconds) * 1_000L
    catalog.cacheTtlMs = maxOf(OnlinePlaybackCatalog.STREAM_URL_TTL_MS, chainMs + TTL_MARGIN_MS)
  }

  private fun currentRef(): OnlineChapterRef? {
    val mediaId = player.currentMediaItem?.mediaId?.toMediaIdOrNull() as? MediaId.Chapter ?: return null
    if (!catalog.isOnlineBookId(mediaId.bookId)) return null
    return OnlineUri.parse(mediaId.chapterId.value)
  }
}

/** Small buffer on top of the computed chain duration for the ttl widening. */
private const val TTL_MARGIN_MS = 2 * 60_000L
