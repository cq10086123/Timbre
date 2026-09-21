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
  private val onDurationResolved: (OnlineChapterRef, Long) -> Unit = { _, _ -> },
) : BaseDataSource(false) {

  private companion object {
    /** Enough to cover an id3 tag plus a few audio frames for the probe. */
    const val PROBE_BUFFER_BYTES = 256 * 1024
  }

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
    // buffer the stream so the duration probe can peek at the first frames
    // and reset before regular reads start
    val buffered = java.io.BufferedInputStream(body.byteStream(), PROBE_BUFFER_BYTES)
    val contentLength = body.contentLength()
    if (contentLength > 0) {
      probeDuration(dataSpec, ref, contentLength, buffered)
    }
    inputStream = buffered
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

  /**
   * Peeks at the first frames of a fresh full-chapter stream to measure the
   * real duration; sources that do not report durations would otherwise clip
   * playback to a placeholder. Only runs once per chapter open from position
   * zero, where Content-Length covers the whole file.
   */
  private fun probeDuration(
    dataSpec: DataSpec,
    ref: OnlineChapterRef,
    contentLength: Long,
    stream: java.io.BufferedInputStream,
  ) {
    try {
      if (dataSpec.position != 0L || dataSpec.length != C.LENGTH_UNSET.toLong()) return
      stream.mark(PROBE_BUFFER_BYTES)
      val head = ByteArray(PROBE_BUFFER_BYTES)
      var read = 0
      while (read < head.size) {
        val n = stream.read(head, read, head.size - read)
        if (n == -1) break
        read += n
      }
      stream.reset()
      val durationMs = OnlineStreamDurationProbe.estimateDurationMs(contentLength, head.copyOf(read))
      if (durationMs != null && durationMs > 0) {
        onDurationResolved(ref, durationMs)
      }
    } catch (_: Exception) {
      // probing is best effort; never break playback over it
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
  private val onDurationResolved: (OnlineChapterRef, Long) -> Unit = { _, _ -> },
) : DataSource.Factory {

  override fun createDataSource(): DataSource {
    return OnlineStreamingDataSource(okHttpClient, baseUrlProvider, tokenProvider, urlResolver, onDurationResolved)
  }
}
