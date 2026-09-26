package voice.core.online

import android.app.Application
import android.net.ConnectivityManager
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import voice.core.common.DispatcherProvider
import voice.core.data.BookId
import voice.core.data.ChapterId
import voice.core.logging.api.Logger
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.coroutines.coroutineContext
import kotlin.math.min

/**
 * Measures the durations of the chapters right after the playing one, before
 * they are opened. Sources that report no durations (their chapters would
 * otherwise sit on the 30 minute placeholder until first play) get corrected
 * while the user listens, so the chapter list and the progress bar turn exact
 * ahead of playback. Measurements go through the catalog and are persisted
 * per source, exactly like the in-playback probe.
 *
 * Bounded on purpose: a handful of head fetches per chapter turn, never on
 * metered networks, failures only skip the chapter.
 */
@SingleIn(AppScope::class)
@Inject
public class OnlineDurationProbeAhead internal constructor(
  private val router: OnlineSourceRouter,
  private val catalog: OnlinePlaybackCatalog,
  private val context: Application,
  @OnlineSourceStreamingClient private val httpClient: OkHttpClient,
  dispatcherProvider: DispatcherProvider,
) {

  private val scope = CoroutineScope(SupervisorJob() + dispatcherProvider.io)

  /** Fire-and-forget: measures the next unknown chapters after [chapterId]. */
  public fun probeUpcoming(
    bookId: BookId,
    chapterId: ChapterId,
  ) {
    scope.launch {
      try {
        probeUpcomingInternal(bookId, chapterId)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Logger.w("Online duration probe-ahead failed: $e")
      }
    }
  }

  internal suspend fun probeUpcomingInternal(
    bookId: BookId,
    chapterId: ChapterId,
  ) {
    val bookRef = OnlineUri.parseBookUri(bookId.value) ?: return
    if (isMetered()) return
    val book = catalog.lookupOnlineBook(bookRef.source, bookRef.bookId) ?: return
    val chapters = book.chapters.ifEmpty {
      runCatching { router.chapters(bookRef.source, bookRef.bookId) }.getOrDefault(emptyList())
    }
    val currentId = OnlineUri.parse(chapterId.value)?.chapterId ?: chapterId.value
    val index = chapters.indexOfFirst { it.id == currentId }
    if (index < 0) return
    chapters.drop(index + 1)
      .filter {
        it.durationSeconds <= 0 &&
          catalog.measuredDurationMs(bookRef.source, bookRef.bookId, it.id) == null
      }
      .take(PROBE_AHEAD_COUNT)
      .forEach { chapter ->
        coroutineContext.ensureActive()
        probeChapter(bookRef.source, bookRef.bookId, chapter)
      }
  }

  private suspend fun probeChapter(
    source: String,
    bookId: String,
    chapter: OnlineChapter,
  ) {
    val audio = runCatching {
      router.resolveAudio(source, bookId, chapter.id, chapter.extra)
    }.getOrNull()
      ?: return
    if (audio.url.isBlank()) return
    val durationMs = runCatching { probeUrl(audio.url, audio.headers) }.getOrNull() ?: return
    if (durationMs > 0L) {
      catalog.recordMeasuredDuration(source, bookId, chapter.id, durationMs)
    }
  }

  /**
   * Measures one remote file: a single ranged head fetch, no full download.
   * Returns null when the server ignores ranges, hides the total size, or the
   * head holds no usable frame.
   */
  internal fun probeUrl(url: String, headers: Map<String, String> = emptyMap()): Long? {
    val builder = Request.Builder()
      .url(url)
    for ((name, value) in headers) {
      if (name.isNotBlank() && value.isNotBlank()) builder.header(name, value)
    }
    val request = builder
      .header("Range", "bytes=0-${PROBE_BYTES - 1}")
      .build()
    httpClient.newCall(request).execute().use { response ->
      if (!response.isSuccessful && response.code != 206) return null
      // for a 206 the body length is the window, not the file: the total only
      // comes from Content-Range. A 200 (range ignored) carries the total in
      // Content-Length instead.
      val total = response.header("Content-Range")
        ?.substringAfterLast('/')
        ?.toLongOrNull()
        ?: response.body.contentLength().takeIf { response.code == 200 && it > 0L }
        ?: return null
      val head = readHead(response.body.byteStream())
      if (head.size < 8) return null
      return OnlineStreamDurationProbe.estimateDurationMs(total, head)
    }
  }

  private fun readHead(stream: InputStream): ByteArray {
    val out = ByteArrayOutputStream(PROBE_BYTES)
    val buffer = ByteArray(8192)
    var remaining = PROBE_BYTES
    while (remaining > 0) {
      val read = stream.read(buffer, 0, min(buffer.size, remaining))
      if (read == -1) break
      out.write(buffer, 0, read)
      remaining -= read
    }
    return out.toByteArray()
  }

  private fun isMetered(): Boolean {
    val manager = context.getSystemService(ConnectivityManager::class.java) ?: return true
    return manager.isActiveNetworkMetered
  }

  internal companion object {
    /** Enough for an id3 tag plus the first frames of one head fetch. */
    internal const val PROBE_BYTES = 64 * 1024

    /** Unknown chapters measured per chapter turn; the rest follow next turn. */
    internal const val PROBE_AHEAD_COUNT = 5
  }
}
