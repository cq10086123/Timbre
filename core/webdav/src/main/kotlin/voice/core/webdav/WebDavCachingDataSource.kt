package voice.core.webdav

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.SimpleCache

/**
 * Routes http(s) requests through a [CacheDataSource] backed by the playback
 * cache and passes everything else straight through to the upstream, so local
 * books never enter the webdav cache.
 */
internal class WebDavCachingDataSource(
  private val upstreamFactory: DataSource.Factory,
  private val cacheProvider: () -> SimpleCache?,
) : DataSource {

  private val listeners = mutableListOf<TransferListener>()
  private var delegate: DataSource? = null

  override fun addTransferListener(transferListener: TransferListener) {
    listeners += transferListener
    delegate?.addTransferListener(transferListener)
  }

  override fun open(dataSpec: DataSpec): Long {
    val cache = cacheProvider()
    val useCache = cache != null && dataSpec.uri.scheme in setOf("http", "https")
    val source = if (useCache && cache != null) {
      CacheDataSource.Factory()
        .setCache(cache)
        .setUpstreamDataSourceFactory(upstreamFactory)
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        .build()
    } else {
      upstreamFactory.createDataSource()
    }
    delegate = source
    listeners.forEach(source::addTransferListener)
    return source.open(dataSpec)
  }

  override fun read(
    target: ByteArray,
    offset: Int,
    length: Int,
  ): Int {
    return checkNotNull(delegate).read(target, offset, length)
  }

  override fun close() {
    delegate?.close()
    delegate = null
  }

  override fun getUri(): Uri? {
    return delegate?.uri
  }

  override fun getResponseHeaders(): Map<String, List<String>> {
    return delegate?.responseHeaders ?: emptyMap()
  }
}
