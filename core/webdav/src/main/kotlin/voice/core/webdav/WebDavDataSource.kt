package voice.core.webdav

import android.app.Application
import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.TransferListener
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * Data source factory that injects the WebDAV credentials of the matching
 * server into http(s) requests and passes everything else through unchanged.
 */
@SingleIn(AppScope::class)
@Inject
public class WebDavDataSourceFactory internal constructor(
  private val resolver: WebDavCredentialResolver,
  private val client: WebDavClient,
  private val classifier: WebDavSpanClassifier,
  context: Application,
) : DataSource.Factory {

  private val delegateFactory: DefaultDataSource.Factory = DefaultDataSource.Factory(
    context,
    DefaultHttpDataSource.Factory()
      .setAllowCrossProtocolRedirects(true)
      .setConnectTimeoutMs(CONNECT_TIMEOUT_MS)
      .setReadTimeoutMs(READ_TIMEOUT_MS),
  )

  /**
   * Playback path. Marks the uri as consumed, so cached data that was actually
   * played loses its speculative classification.
   */
  override fun createDataSource(): DataSource {
    return WebDavDataSource(resolver, client, delegateFactory, classifier, markConsumed = true)
  }

  /** Upstream for the prefetcher, which manages the speculative classification itself. */
  fun createUnmarkedDataSource(): DataSource {
    return WebDavDataSource(resolver, client, delegateFactory, classifier, markConsumed = false)
  }

  private companion object {
    const val CONNECT_TIMEOUT_MS = 10_000
    const val READ_TIMEOUT_MS = 30_000
  }
}

internal class WebDavDataSource(
  private val resolver: WebDavCredentialResolver,
  private val client: WebDavClient,
  private val delegateFactory: DataSource.Factory,
  private val classifier: WebDavSpanClassifier,
  private val markConsumed: Boolean,
) : DataSource {

  private val listeners = mutableListOf<TransferListener>()
  private var delegate: DataSource? = null

  override fun addTransferListener(transferListener: TransferListener) {
    listeners += transferListener
    delegate?.addTransferListener(transferListener)
  }

  override fun open(dataSpec: DataSpec): Long {
    val source = delegateFactory.createDataSource()
    delegate = source
    listeners.forEach(source::addTransferListener)
    return source.open(withAuth(dataSpec))
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

  private fun withAuth(dataSpec: DataSpec): DataSpec {
    val resolved = resolver.byUri(dataSpec.uri) ?: return dataSpec
    if (markConsumed) {
      classifier.markConsumed(dataSpec.uri.toString())
    }
    val headers = buildMap {
      putAll(dataSpec.httpRequestHeaders)
      put("Authorization", client.basicAuth(resolved.server.username, resolved.password))
    }
    return dataSpec.buildUpon()
      .setHttpRequestHeaders(headers)
      .build()
  }
}
