package voice.core.update

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL

@Inject
class UpdateChecker {

  private val json = Json {
    ignoreUnknownKeys = true
  }

  // jsDelivr first: it is a CDN that is reachable from mainland China, where
  // github often is not, and it serves the update-pure.json this branch keeps.
  // Every endpoint is tried in order and a failure or a timeout of one is
  // silently ignored. The pure branch deliberately does NOT fall back to the
  // GitHub releases/latest API, because that endpoint is repository-wide and
  // would return mainline (online-enabled) releases. Using only the branch-bound
  // update-pure.json keeps users of the offline-only flavour on the pure line.
  private val endpoints = listOf(
    "https://cdn.jsdelivr.net/gh/cq10086123/Timbre@pure/update-pure.json",
    "https://fastly.jsdelivr.net/gh/cq10086123/Timbre@pure/update-pure.json",
  )

  /**
   * The versionName of the latest release, or null when none of the endpoints
   * answered. Null is the normal case for a device without internet access,
   * the caller treats it as "no update".
   */
  suspend fun latestVersion(): String? = withContext(Dispatchers.IO) {
    endpoints.firstNotNullOfOrNull { endpoint ->
      runCatching { fetchVersion(endpoint) }.getOrNull()
    }
  }

  private fun fetchVersion(endpoint: String): String? {
    val connection = URL(endpoint).openConnection() as HttpURLConnection
    connection.connectTimeout = CONNECT_TIMEOUT_MS
    connection.readTimeout = READ_TIMEOUT_MS
    connection.instanceFollowRedirects = true
    return try {
      val body = if (connection.responseCode in HTTP_OK..HTTP_OK) {
        connection.inputStream.bufferedReader().use { reader ->
          reader.readText()
        }
      } else {
        null
      }
      body?.let { parseVersion(endpoint, it) }
    } finally {
      connection.disconnect()
    }
  }

  private fun parseVersion(
    endpoint: String,
    body: String,
  ): String? {
    val element = json.parseToJsonElement(body)
    return element.jsonObject[VERSION_NAME_KEY]?.jsonPrimitive?.content
  }

  private companion object {
    const val JSON_FILE_NAME = "update-pure.json"
    const val VERSION_NAME_KEY = "versionName"
    const val HTTP_OK = 200
    const val CONNECT_TIMEOUT_MS = 5_000
    const val READ_TIMEOUT_MS = 8_000
  }
}
