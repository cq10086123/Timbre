package voice.core.webdav

import android.app.Application
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.TransferListener
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InputStream

/**
 * Data source factory that injects WebDAV credentials into remote requests.
 *
 * Local content continues to use Media3's DefaultDataSource. Remote content is
 * opened through the WebDavClient's OkHttp client so a Digest challenge can be
 * answered by the same authenticator as PROPFIND and probe requests.
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

  /** Playback path. Marks the resource as consumed for tiered cache eviction. */
  override fun createDataSource(): DataSource {
    return WebDavDataSource(resolver, client, delegateFactory, classifier, markConsumed = true)
  }

  /** Upstream for the prefetcher, which manages speculative classification itself. */
  public fun createUnmarkedDataSource(): DataSource {
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
  private var response: Response? = null
  private var input: InputStream? = null
  private var bytesRemaining: Long = C.LENGTH_UNSET.toLong()
  private var remoteUri: Uri? = null

  override fun addTransferListener(transferListener: TransferListener) {
    listeners += transferListener
    delegate?.addTransferListener(transferListener)
  }

  override fun open(dataSpec: DataSpec): Long {
    close()
    val resolved = resolver.byUri(dataSpec.uri)
    if (resolved == null) {
      val source = delegateFactory.createDataSource()
      delegate = source
      listeners.forEach(source::addTransferListener)
      return source.open(dataSpec)
    }

    if (markConsumed) classifier.markConsumed(dataSpec.uri.toString())
    val request = Request.Builder()
      .url(dataSpec.uri.toString())
      .apply {
        dataSpec.httpRequestHeaders.forEach { (name, value) -> header(name, value) }
        if (dataSpec.httpRequestHeaders.keys.none { it.equals("Authorization", ignoreCase = true) }) {
          header("Authorization", client.basicAuth(resolved.server.username, resolved.password))
        }
        // Media3's DefaultHttpDataSource uses byte ranges for seeking. Keep
        // this explicit so Digest authentication signs the exact GET that is
        // sent to a WebDAV server.
        if (dataSpec.position != 0L || dataSpec.length != C.LENGTH_UNSET.toLong()) {
          val end = if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
            ""
          } else {
            (dataSpec.position + dataSpec.length - 1).toString()
          }
          header("Range", "bytes=${dataSpec.position}-$end")
        }
        header("Accept-Encoding", "identity")
      }
      .get()
      .build()

    val opened = client.clientFor(resolved.server, resolved.password)
      .newCall(request)
      .execute()
    response = opened
    remoteUri = Uri.parse(opened.request.url.toString())
    if (!opened.isSuccessful) {
      opened.close()
      response = null
      throw IOException("WebDAV GET ${dataSpec.uri} returned HTTP ${opened.code}")
    }
    val body = opened.body ?: run {
      opened.close()
      response = null
      throw IOException("WebDAV GET ${dataSpec.uri} returned an empty body")
    }
    input = body.byteStream()

    // A compliant server answers a ranged request with 206. For a server that
    // ignores Range, discard the prefix so seeking still has correct semantics
    // (the probe warns about this case when adding the server).
    if (opened.code == 200 && dataSpec.position > 0L) {
      skipFully(input!!, dataSpec.position)
    }
    val bodyLength = body.contentLength()
    bytesRemaining = when {
      dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
      bodyLength >= 0L -> bodyLength - if (opened.code == 200) dataSpec.position else 0L
      else -> C.LENGTH_UNSET.toLong()
    }
    return bytesRemaining
  }

  override fun read(
    target: ByteArray,
    offset: Int,
    length: Int,
  ): Int {
    delegate?.let { return it.read(target, offset, length) }
    val stream = input ?: throw IllegalStateException("open() must be called before read()")
    if (length == 0) return 0
    val requested = if (bytesRemaining == C.LENGTH_UNSET.toLong()) {
      length
    } else {
      minOf(length.toLong(), bytesRemaining).toInt()
    }
    if (requested == 0) return C.RESULT_END_OF_INPUT
    val read = stream.read(target, offset, requested)
    if (read == -1) {
      bytesRemaining = 0L
      return C.RESULT_END_OF_INPUT
    }
    if (bytesRemaining != C.LENGTH_UNSET.toLong()) bytesRemaining -= read
    return read
  }

  override fun close() {
    runCatching { input?.close() }
    input = null
    runCatching { response?.close() }
    response = null
    delegate?.close()
    delegate = null
    bytesRemaining = C.LENGTH_UNSET.toLong()
    remoteUri = null
  }

  override fun getUri(): Uri? {
    return delegate?.uri ?: remoteUri
  }

  override fun getResponseHeaders(): Map<String, List<String>> {
    return delegate?.responseHeaders ?: response?.headers?.let { headers ->
      headers.names().associateWith { name -> headers.values(name) }
    }.orEmpty()
  }

  private fun skipFully(stream: InputStream, bytes: Long) {
    var remaining = bytes
    while (remaining > 0) {
      val skipped = stream.skip(remaining)
      if (skipped > 0) {
        remaining -= skipped
        continue
      }
      if (stream.read() == -1) throw IOException("WebDAV server ended the response while seeking")
      remaining--
    }
  }
}
