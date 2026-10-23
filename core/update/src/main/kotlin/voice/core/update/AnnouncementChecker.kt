package voice.core.update

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches the announcement the maintainers write into `announcement.txt` on
 * the main branch. Plain text on purpose: whatever is in the file is what the
 * dialog shows, no parsing, no format to keep in sync with the app.
 *
 * The endpoints: raw.githubusercontent reflects main within a minute, jsDelivr
 * is reachable from mainland China where github is not, and caching is harmless
 * here because a cached announcement is still the announcement the user was
 * supposed to see.
 */
@Inject
class AnnouncementChecker {

  /**
   * The current announcement, or null when there is none (blank file) or no
   * endpoint answered. Null is the normal case offline and shows no dialog.
   */
  suspend fun current(): String? = withContext(Dispatchers.IO) {
    endpoints.firstNotNullOfOrNull { endpoint ->
      runCatching { fetch(endpoint) }.getOrNull()
    }
  }

  private fun fetch(endpoint: String): String? {
    val connection = URL(endpoint).openConnection() as HttpURLConnection
    connection.connectTimeout = CONNECT_TIMEOUT_MS
    connection.readTimeout = READ_TIMEOUT_MS
    connection.instanceFollowRedirects = true
    return try {
      if (connection.responseCode != HTTP_OK) return null
      val text = connection.inputStream.bufferedReader().use { reader ->
        // a maintainer pasting a whole changelog must not produce a dialog
        // the app cannot lay out: read a bounded prefix, the rest is cut
        val buffer = CharArray(MAX_CHARS)
        var read = 0
        while (read < buffer.size) {
          val n = reader.read(buffer, read, buffer.size - read)
          if (n == -1) break
          read += n
        }
        String(buffer, 0, read)
      }
      normalize(text)
    } finally {
      connection.disconnect()
    }
  }

  internal fun normalize(text: String): String? {
    return text
      .replace("\r\n", "\n")
      .replace('\r', '\n')
      .trim()
      .takeIf { it.isNotEmpty() }
  }

  private companion object {
    val endpoints = listOf(
      "https://raw.githubusercontent.com/cq10086123/Timbre/main/announcement.txt",
      "https://cdn.jsdelivr.net/gh/cq10086123/Timbre@main/announcement.txt",
      "https://fastly.jsdelivr.net/gh/cq10086123/Timbre@main/announcement.txt",
    )

    const val HTTP_OK = 200
    const val CONNECT_TIMEOUT_MS = 5_000
    const val READ_TIMEOUT_MS = 8_000
    const val MAX_CHARS = 8_000
  }
}
