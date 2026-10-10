package voice.core.online

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.math.min

/**
 * Measures one remote audio file with a single ranged head fetch, no full
 * download. The fetch goes against the audio file host (a CDN), not the
 * source api, so it does not participate in source rate limits.
 */
public object OnlineStreamHeadProbe {

  /** Enough for an id3 tag plus the first frames of one head fetch. */
  public const val PROBE_BYTES: Int = 64 * 1024

  /**
   * Returns the estimated duration in ms, or null when the server ignores
   * ranges, hides the total size, or the head holds no usable frame.
   */
  public fun probeDurationMs(
    url: String,
    sourceHeaders: Map<String, String> = emptyMap(),
    httpClient: OkHttpClient,
  ): Long? {
    val requestBuilder = Request.Builder()
      .url(url)
      .header("Range", "bytes=0-${PROBE_BYTES - 1}")
    for ((name, value) in sourceHeaders) {
      requestBuilder.header(name, value)
    }
    httpClient.newCall(requestBuilder.build()).execute().use { response ->
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
}
