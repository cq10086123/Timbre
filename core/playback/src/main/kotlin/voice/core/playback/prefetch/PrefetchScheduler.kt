package voice.core.playback.prefetch

import android.app.Application
import android.net.ConnectivityManager
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import voice.core.common.DispatcherProvider
import voice.core.common.PlaybackIoGate
import voice.core.data.Book
import voice.core.data.BookId
import voice.core.data.Chapter
import voice.core.data.repo.BookRepository
import voice.core.data.store.CurrentBookStore
import voice.core.data.toUri
import voice.core.logging.api.Logger
import voice.core.playback.playstate.PlayStateManager
import voice.core.webdav.WebDavCacheSettings
import voice.core.webdav.WebDavDataSourceFactory
import voice.core.webdav.WebDavPlaybackCache

/**
 * Prefetches the chapters after the current one of the current book into the
 * playback cache until the configured limit is reached. Playback always wins:
 * whenever the player buffers, the prefetch loop pauses.
 */
@SingleIn(AppScope::class)
@Inject
internal class PrefetchScheduler(
  private val playbackCache: WebDavPlaybackCache,
  private val webDavDataSourceFactory: WebDavDataSourceFactory,
  private val bookRepository: BookRepository,
  private val playStateManager: PlayStateManager,
  private val playbackIoGate: PlaybackIoGate,
  private val context: Application,
  @CurrentBookStore private val currentBookStore: DataStore<BookId?>,
  dispatcherProvider: DispatcherProvider,
) {

  private val scope = CoroutineScope(SupervisorJob() + dispatcherProvider.io)
  private var started = false

  fun start() {
    if (started) return
    started = true
    scope.launch {
      currentBookStore.data.collectLatest { bookId ->
        if (bookId == null) {
          playbackCache.markActiveBook(null)
          return@collectLatest
        }
        // mark the active book even when it is a local one, so prefetched data
        // of webdav books left halfway is recognized as inactive
        playbackCache.markActiveBook(bookId.toUri().toString())
        val book = bookRepository.get(bookId) ?: return@collectLatest
        if (book.chapters.isEmpty()) return@collectLatest
        if (!isWebDavUrl(book.id.toUri().toString())) return@collectLatest
        prefetchBook(book)
      }
    }
  }

  private suspend fun prefetchBook(book: Book) {
    var lastChapterUrl: String? = null
    while (currentCoroutineContext().isActive) {
      val settings = playbackCache.settings().data.first()
      if (settings.maxBytes <= 0L || settings.prefetchMode == WebDavCacheSettings.PrefetchMode.Disabled) {
        return
      }
      val content = bookRepository.get(book.id)?.content ?: return

      // playback of the current chapter consumes its cached data, even when
      // the CacheDataSource served it without opening the upstream
      val currentUrl = book.chapters.firstOrNull { it.id == content.currentChapter }
        ?.id?.toUri()?.toString()
      if (currentUrl != null && currentUrl != lastChapterUrl) {
        playbackCache.markConsumed(currentUrl)
        lastChapterUrl = currentUrl
      }

      val cache = playbackCache.cacheOrNull() ?: return
      if (cache.cacheSpace >= settings.maxBytes - HEADROOM_BYTES) {
        delay(REFILL_CHECK_MS)
        continue
      }
      if (playStateManager.playState != PlayStateManager.PlayState.Playing) {
        delay(PAUSED_CHECK_MS)
        continue
      }
      if (isMeteredNetwork() && !settings.prefetchOnMetered) {
        delay(METERED_CHECK_MS)
        continue
      }

      val index = book.chapters.indexOfFirst { it.id == content.currentChapter }
      val upcoming = book.chapters.drop(index + 1)
      val window = when (settings.prefetchMode) {
        WebDavCacheSettings.PrefetchMode.Disabled -> return
        WebDavCacheSettings.PrefetchMode.Chapters5 -> upcoming.take(5)
        WebDavCacheSettings.PrefetchMode.Chapters20 -> upcoming.take(20)
        WebDavCacheSettings.PrefetchMode.FillBook -> upcoming
      }

      val fetchedSomething = prefetchWindow(cache, settings, book, window)
      if (!fetchedSomething) {
        // nothing left to fetch: everything cached, the limit is reached or
        // the network is failing. Wait for the next position change.
        delay(REFILL_CHECK_MS)
      }
    }
  }

  /** Returns true when at least one chapter chunk was fetched. */
  private suspend fun prefetchWindow(
    cache: Cache,
    settings: WebDavCacheSettings,
    book: Book,
    window: List<Chapter>,
  ): Boolean {
    var fetchedAny = false
    for (chapter in window) {
      if (cache.cacheSpace >= settings.maxBytes - HEADROOM_BYTES) {
        return fetchedAny
      }
      val url = chapter.id.toUri().toString()
      val length = chapter.fileSize
      if (length > 0 && cache.getCachedBytes(url, 0L, length) >= length) {
        continue
      }

      playbackCache.markSpeculative(url, book.id.toUri().toString())
      // the import pauses while the player buffers and so does the prefetch:
      // the nas bandwidth belongs to the playback first
      playbackIoGate.whilePlaybackLoads { }
      val fetched = try {
        cacheChapter(cache, url)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Logger.w(e, "Prefetch failed for $url")
        0L
      }
      if (fetched > 0L) {
        fetchedAny = true
      } else {
        // one failed chapter should not spin the loop hot
        delay(FAILURE_BACKOFF_MS)
      }
    }
    return fetchedAny
  }

  /** Downloads [url] into the cache and returns the number of bytes written. */
  private suspend fun cacheChapter(
    cache: Cache,
    url: String,
  ): Long {
    val unmarkedUpstream = DataSource.Factory { webDavDataSourceFactory.createUnmarkedDataSource() }
    val cacheDataSource = CacheDataSource.Factory()
      .setCache(cache)
      .setUpstreamDataSourceFactory(unmarkedUpstream)
      .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
      .build()
    val dataSpec = DataSpec.Builder()
      .setUri(Uri.parse(url))
      .setLength(C.LENGTH_UNSET.toLong())
      .build()
    val writer = CacheWriter(cacheDataSource, dataSpec, /* temporaryBuffer= */ null, /* progressListener= */ null)
    // skips already cached ranges on its own and returns without a result
    // value, so the total is read back from the cache afterwards
    writer.cache()
    return cache.getCachedBytes(url, 0L, C.LENGTH_UNSET.toLong())
  }

  private fun isMeteredNetwork(): Boolean {
    val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
      ?: return true
    return connectivityManager.isActiveNetworkMetered
  }

  private fun isWebDavUrl(url: String): Boolean {
    return url.startsWith("http://") || url.startsWith("https://")
  }

  private companion object {
    const val HEADROOM_BYTES = 16L * 1024L * 1024L
    const val REFILL_CHECK_MS = 10_000L
    const val PAUSED_CHECK_MS = 5_000L
    const val METERED_CHECK_MS = 30_000L
    const val FAILURE_BACKOFF_MS = 2_000L
  }
}
