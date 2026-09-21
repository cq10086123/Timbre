package voice.core.online

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Streams a direct audio url with byte-range support. The url is resolved
 * per chapter by [OnlineSourceService] (script source direct link, or the
 * download site's file endpoint); the resolver is injected by the factory.
 */
public class OnlineStreamingDataSource internal constructor(
  private val okHttpClient: OkHttpClient,
  private val baseUrlProvider: () -> String,
  private val tokenProvider: () -> String,
  private val urlResolver: (OnlineChapterRef) -> String?,
) : BaseDataSource(false) {

  private var response: okhttp3.Response? = null
  private var inputStream: java.io.InputStream? = null
  private var bytesRemaining: Long = -1L // unset
  private var openedUri: Uri? = null

  override fun open(dataSpec: DataSpec): Long {
    transferInitializing(dataSpec)
    val ref = dataSpec.toOnlineChapterRef()
      ?: throw IOException("Not an online chapter uri: $uri")
    val requestUrl = urlResolver(ref)
      ?: throw HttpDataSource.InvalidResponseCodeException(
        404,
        dataSpec.uri.toString(),
        null,
        emptyMap(),
        dataSpec,
        ByteArray(0),
      )
    val requestBuilder = Request.Builder().url(requestUrl)
    // the site's file streaming endpoint requires the bearer token; third
    // party cdn links must not receive it
    val base = baseUrlProvider().trim().trimEnd('/')
    val token = tokenProvider().trim()
    if (token.isNotEmpty() && requestUrl.startsWith(base)) {
      requestBuilder.header("Authorization", "Bearer $token")
    }
    val rangeStart = dataSpec.position
    if (rangeStart > 0 || dataSpec.length != C.LENGTH_UNSET.toLong()) {
      // some cdns answer closed ranges (bytes=start-end) with an empty body,
      // so always request an open ended range and cap the read length here
      requestBuilder.header("Range", "bytes=$rangeStart-")
    }
    val response = okHttpClient.newCall(requestBuilder.build()).execute()
    if (!response.isSuccessful && response.code != 206) {
      val code = response.code
      response.close()
      throw HttpDataSource.InvalidResponseCodeException(
        code,
        requestUrl,
        null,
        response.headers.toMultimap(),
        dataSpec,
        ByteArray(0),
      )
    }
    this.response = response
    openedUri = Uri.parse(requestUrl)
    val body = response.body
    inputStream = body.byteStream()
    val contentLength = body.contentLength()
    // the open ended range returns the full remaining stream; cap it to the
    // requested window so read() stops exactly at dataSpec.length
    bytesRemaining = when {
      dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
      contentLength >= 0 -> contentLength
      else -> -1L
    }
    transferStarted(dataSpec)
    return when {
      dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
      bytesRemaining >= 0 -> bytesRemaining
      else -> -1L
    }
  }

  override fun read(
    buffer: ByteArray,
    offset: Int,
    length: Int,
  ): Int {
    if (length == 0) return 0
    val stream = inputStream ?: throw IOException("Not opened")
    if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
    val toRead = if (bytesRemaining in 1 until length.toLong()) bytesRemaining.toInt() else length
    val read = stream.read(buffer, offset, toRead)
    if (read == -1) return C.RESULT_END_OF_INPUT
    if (bytesRemaining > 0) bytesRemaining -= read
    bytesTransferred(read)
    return read
  }

  override fun getUri(): Uri? = openedUri

  override fun close() {
    try {
      inputStream?.close()
    } catch (_: IOException) {
    }
    response?.close()
    inputStream = null
    response = null
  }

  private fun DataSpec.toOnlineChapterRef(): OnlineChapterRef? {
    return OnlineUri.parse(uri.toString())
  }
}

/** Factory mirroring the webdav module's data source factory. */
public class OnlineDataSourceFactory internal constructor(
  private val okHttpClient: OkHttpClient,
  private val baseUrlProvider: () -> String,
  private val tokenProvider: () -> String,
  private val urlResolver: (OnlineChapterRef) -> String?,
) : DataSource.Factory {

  override fun createDataSource(): DataSource {
    return OnlineStreamingDataSource(okHttpClient, baseUrlProvider, tokenProvider, urlResolver)
  }
}
