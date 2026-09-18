package voice.core.webdav

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.SimpleCache
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import voice.core.logging.api.Logger
import java.io.File

/**
 * Holds the process wide [SimpleCache] for webdav playback and hands out data
 * source factories that read through the cache. Disabled settings bypass the
 * cache without tearing it down, because a SimpleCache cannot be recreated
 * safely at runtime.
 */
@SingleIn(AppScope::class)
@Inject
public class WebDavPlaybackCache(
  private val context: Application,
  @WebDavCacheSettingsStore private val settingsStore: DataStore<WebDavCacheSettings>,
  private val classifier: WebDavSpanClassifier,
) {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  @Volatile
  private var maxBytes: Long = WebDavCacheSettings.DEFAULT_MAX_BYTES

  private val lock = Any()

  @Volatile
  private var simpleCache: SimpleCache? = null

  private val evictor by lazy {
    WebDavCacheEvictor(maxBytes = { maxBytes }, classifier = classifier)
  }

  init {
    settingsStore.data
      .onEach { settings ->
        val previous = maxBytes
        maxBytes = settings.maxBytes
        if (settings.maxBytes in 1 until previous) {
          // the limit was lowered: shrink immediately instead of waiting for
          // the next write
          simpleCache?.let { evictor.evictIfNeeded(it) }
        }
      }
      .launchIn(scope)
  }

  /**
   * Wraps [upstream] with the cache. Only http(s) responses (i.e. webdav) are
   * cached, local playback passes straight through. Returns [upstream]
   * unchanged when caching is disabled.
   */
  public fun dataSourceFactory(upstream: DataSource.Factory): DataSource.Factory {
    return DataSource.Factory {
      WebDavCachingDataSource(upstream) { cacheOrNull() }
    }
  }

  public fun cacheOrNull(): SimpleCache? {
    synchronized(lock) {
      simpleCache?.let { return it }
      if (maxBytes <= 0L) return null
      return try {
        SimpleCache(
          File(context.cacheDir, CACHE_DIR),
          evictor,
          StandaloneDatabaseProvider(context),
        ).also {
          simpleCache = it
        }
      } catch (e: Exception) {
        Logger.w(e, "Could not create the webdav playback cache")
        null
      }
    }
  }

  /** Total size of the cached data in bytes. */
  public fun cachedBytes(): Long {
    return simpleCache?.cacheSpace ?: 0L
  }

  public suspend fun clear() {
    val cache = simpleCache ?: return
    for (key in cache.keys) {
      cache.removeResource(key)
    }
  }

  /** Removes all cached resources whose url starts with [prefix]. */
  public fun removeByPrefix(prefix: String) {
    val cache = simpleCache ?: return
    val normalized = prefix.trimEnd('/')
    for (key in cache.keys) {
      if (key.startsWith(normalized)) {
        cache.removeResource(key)
      }
    }
  }

  public fun settings(): DataStore<WebDavCacheSettings> {
    return settingsStore
  }

  private companion object {
    const val CACHE_DIR = "webdav_cache"
  }
}
