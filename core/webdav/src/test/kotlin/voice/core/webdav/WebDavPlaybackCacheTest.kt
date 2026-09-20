package voice.core.webdav

import android.app.Application
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class WebDavPlaybackCacheTest {

  @Test
  fun disablingTheCacheClearsBytesFromDisk(): Unit = runBlocking {
    val settings = MemoryDataStore(WebDavCacheSettings(maxBytes = 1024L * 1024L))
    val playbackCache = WebDavPlaybackCache(
      context = ApplicationProvider.getApplicationContext<Application>(),
      settingsStore = settings,
      classifier = WebDavSpanClassifier(),
    )
    val cache = playbackCache.cacheOrNull()!!
    // write 512 bytes through the same CacheWriter path the prefetcher uses
    val upstream = DataSource.Factory { ByteArrayDataSource(ByteArray(512) { 0x41 }) }
    val cacheDataSource = CacheDataSource.Factory()
      .setCache(cache)
      .setUpstreamDataSourceFactory(upstream)
      .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
      .createDataSource()
    CacheWriter(cacheDataSource, DataSpec(Uri.parse("https://nas/dav/01.mp3")), null, null).cache()
    assertTrue(playbackCache.cachedBytes() > 0L)

    settings.updateData { it.copy(maxBytes = 0L) }

    // the settings observer runs on Dispatchers.IO, so poll for its effect
    withTimeout(10_000L) {
      while (playbackCache.cachedBytes() != 0L) delay(50L)
    }
    assertEquals(expected = 0L, actual = playbackCache.cachedBytes())
  }

  private class ByteArrayDataSource(
    private val bytes: ByteArray,
  ) : DataSource {
    private var position = 0

    override fun open(dataSpec: DataSpec): Long = bytes.size.toLong()

    override fun read(
      target: ByteArray,
      offset: Int,
      length: Int,
    ): Int {
      if (position >= bytes.size) return C.RESULT_END_OF_INPUT
      val count = minOf(length, bytes.size - position)
      bytes.copyInto(target, offset, position, position + count)
      position += count
      return count
    }

    override fun close() = Unit

    override fun getUri(): Uri? = null

    override fun getResponseHeaders(): Map<String, List<String>> = emptyMap()

    override fun addTransferListener(transferListener: TransferListener) = Unit
  }
}
