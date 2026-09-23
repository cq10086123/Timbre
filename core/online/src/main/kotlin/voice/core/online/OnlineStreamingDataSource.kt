package voice.core.online

import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import voice.core.logging.api.Logger
import java.io.File
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
  private val onUrlRejected: (OnlineChapterRef) -> Boolean = { false },
  private val onDurationResolved: (OnlineChapterRef, Long) -> Unit = { _, _ -> },
  private val hasMeasuredDuration: (OnlineChapterRef) -> Boolean = { false },
  private val fileCache: OnlineChapterFileCache? = null,
) : BaseDataSource(true) {

  private companion object {
    /** Enough to cover an id3 tag plus a few audio frames for the probe. */
    const val PROBE_BUFFER_BYTES = 64 * 1024

    /**
     * The probe waits at most this long for the head of the stream. A source
     * that trickles or stalls would otherwise delay the start of the chapter
     * for as long as it takes to fill the probe buffer.
     *
     * Kept short: a measured duration already lives in the catalog after the
     * first successful play, so cold opens should not stall on slow CDNs.
     */
    const val PROBE_TIMEOUT_MS = 400L

    /** Bytes read per step while the probe waits for more of the stream. */
    const val PROBE_STEP_BYTES = 8 * 1024
  }

  private var response: Response? = null
  private var inputStream: java.io.InputStream? = null
  private var bytesRemaining: Long = -1L // unset
  private var openedUri: Uri? = null

  override fun open(dataSpec: DataSpec): Long {
    transferInitializing(dataSpec)
    val ref = dataSpec.toOnlineChapterRef()
      ?: throw IOException("Not an online chapter uri: $uri")
    // a manually cached chapter plays straight from its file: no resolve, no
    // network, so cached episodes keep playing offline
    fileCache?.fileFor(ref)
      ?.takeIf { it.exists() && it.length() > 0L }
      ?.let { return openFile(dataSpec, ref, it) }
    val response = openResponse(dataSpec, ref)
    this.response = response
    openedUri = Uri.parse(response.request.url.toString())
    val body = response.body
    // buffer the stream so the duration probe can peek at the first frames
    // and reset before regular reads start
    val buffered = java.io.BufferedInputStream(body.byteStream(), PROBE_BUFFER_BYTES)
    val contentLength = body.contentLength()
    // skip the probe once a duration is known: it only delays first audio and
    // the catalog already has a correct value from a previous play/probe-ahead
    if (contentLength > 0 && !hasMeasuredDuration(ref)) {
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
   * Serves a manually cached chapter straight from its file, honoring the
   * requested range exactly like the streaming path does. The duration probe
   * runs from the file head as well, so an offline first play still corrects
   * chapters the source reports no duration for.
   */
  private fun openFile(
    dataSpec: DataSpec,
    ref: OnlineChapterRef,
    file: File,
  ): Long {
    val length = file.length()
    // FileInputStream.skip() is allowed to skip fewer bytes than requested.
    // When ExoPlayer seeks repeatedly, stopping at the first short skip makes
    // the cached stream restart from the wrong byte and can look like seeking
    // has stopped working. FileChannel.position() is exact and also lets us
    // clamp a stale seek at EOF instead of returning unrelated data.
    val position = dataSpec.position.coerceIn(0L, length)
    val stream = java.io.FileInputStream(file)
    try {
      stream.channel.position(position)
    } catch (e: IOException) {
      stream.close()
      throw e
    }
    if (!hasMeasuredDuration(ref)) {
      val _ = runCatching {
        java.io.FileInputStream(file).use { head ->
          val buffer = ByteArray(PROBE_BUFFER_BYTES)
          var read = 0
          while (read < buffer.size) {
            val n = head.read(buffer, read, buffer.size - read)
            if (n == -1) break
            read += n
          }
          OnlineStreamDurationProbe.estimateDurationMs(length, buffer.copyOf(read))
            ?.takeIf { it > 0L }
            ?.let { onDurationResolved(ref, it) }
        }
      }
    }
    inputStream = stream
    openedUri = dataSpec.uri
    bytesRemaining = when {
      dataSpec.length != C.LENGTH_UNSET.toLong() ->
        dataSpec.length.coerceAtMost((length - position).coerceAtLeast(0L))
      else -> (length - position).coerceAtLeast(0L)
    }
    transferStarted(dataSpec)
    return when {
      dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
      else -> bytesRemaining
    }
  }

  /**
   * Runs the request for the resolved url. Direct links are signed and expire
   * while a chapter is being listened to; when the server rejects a url that
   * was served from the resolver's cache it is dropped and resolved once more,
   * so an expired link does not turn into a failed chapter.
   */
  private fun openResponse(
    dataSpec: DataSpec,
    ref: OnlineChapterRef,
  ): Response {
    val resolvedUrl = urlResolver(ref)
      ?: throw invalidResponse(404, dataSpec.uri.toString(), emptyMap(), dataSpec)
    val response = execute(dataSpec, resolvedUrl)
    if (response.isSuccessful) return response
    val code = response.code
    val headers = response.headers.toMultimap()
    response.close()
    if (!onUrlRejected(ref)) throw invalidResponse(code, resolvedUrl, headers, dataSpec)
    val refreshedUrl = urlResolver(ref)
    if (refreshedUrl == null || refreshedUrl == resolvedUrl) {
      throw invalidResponse(code, resolvedUrl, headers, dataSpec)
    }
    val retryResponse = execute(dataSpec, refreshedUrl)
    if (retryResponse.isSuccessful) return retryResponse
    val retryCode = retryResponse.code
    val retryHeaders = retryResponse.headers.toMultimap()
    retryResponse.close()
    throw invalidResponse(retryCode, refreshedUrl, retryHeaders, dataSpec)
  }

  /**
   * Runs one request, healing hosts whose TLS certificate is unusable: the
   * https handshake of such a host always fails, so the same address is retried
   * over plain http (cleartext is allowed) and remembered for the session. The
   * host list is learned at runtime, new broken hosts need no code change.
   */
  private fun execute(
    dataSpec: DataSpec,
    url: String,
  ): Response {
    return try {
      okHttpClient.newCall(request(dataSpec, url)).execute()
    } catch (e: IOException) {
      val httpUrl = OnlineStreamUrlPolicy.downgradeToHttp(url)
      if (httpUrl == null || !isCertificateProblem(e)) throw e
      Logger.w("TLS handshake failed for source host, retrying over http: ${e.message}")
      OnlineStreamUrlPolicy.rememberCertBroken(url)
      okHttpClient.newCall(request(dataSpec, httpUrl)).execute()
    }
  }

  private fun isCertificateProblem(e: Throwable): Boolean = generateSequence(e) { it.cause }
    .take(8)
    .any { it is javax.net.ssl.SSLException || it is java.security.cert.CertificateException }

  private fun request(
    dataSpec: DataSpec,
    url: String,
  ): Request {
    // sources occasionally hand out links on hosts with broken certificates;
    // those are served over http as well, so downgrade instead of failing
    val requestUrl = OnlineStreamUrlPolicy.applyCertFallback(url)
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
    return requestBuilder.build()
  }

  private fun invalidResponse(
    code: Int,
    url: String,
    headers: Map<String, List<String>>,
    dataSpec: DataSpec,
  ): HttpDataSource.InvalidResponseCodeException {
    return HttpDataSource.InvalidResponseCodeException(
      code,
      url,
      null,
      headers,
      dataSpec,
      ByteArray(0),
    )
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
      val deadline = SystemClock.elapsedRealtime() + PROBE_TIMEOUT_MS
      while (read < head.size) {
        // bytes the connection already buffered are free, waiting for the rest
        // of the probe buffer is not: a slow source would hold up the start of
        // the chapter by the seconds it needs to fill it
        val available = stream.available()
        if (available <= 0 && SystemClock.elapsedRealtime() >= deadline) break
        val step = if (available > 0) available else PROBE_STEP_BYTES
        val n = stream.read(head, read, minOf(step, head.size - read))
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
  private val onUrlRejected: (OnlineChapterRef) -> Boolean = { false },
  private val onDurationResolved: (OnlineChapterRef, Long) -> Unit = { _, _ -> },
  private val hasMeasuredDuration: (OnlineChapterRef) -> Boolean = { false },
  private val fileCache: OnlineChapterFileCache? = null,
) : DataSource.Factory {

  override fun createDataSource(): DataSource {
    return OnlineStreamingDataSource(
      okHttpClient = okHttpClient,
      baseUrlProvider = baseUrlProvider,
      tokenProvider = tokenProvider,
      urlResolver = urlResolver,
      onUrlRejected = onUrlRejected,
      onDurationResolved = onDurationResolved,
      hasMeasuredDuration = hasMeasuredDuration,
      fileCache = fileCache,
    )
  }
}
