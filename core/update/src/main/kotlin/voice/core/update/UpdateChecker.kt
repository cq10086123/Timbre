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
  // github often is not, and it serves the update.json this repository keeps
  // on main. The GitHub api is the fallback for the case where the CDN still
  // serves a stale file. Every endpoint is tried in order and a failure or a
  // timeout of one is silently ignored.
  private val endpoints = listOf(
    "https://cdn.jsdelivr.net/gh/cq10086123/Timbre@main/update.json",
    "https://fastly.jsdelivr.net/gh/cq10086123/Timbre@main/update.json",
    "https://api.github.com/repos/cq10086123/Timbre/releases/latest",
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
    return if (endpoint.endsWith(JSON_FILE_NAME)) {
      element.jsonObject[VERSION_NAME_KEY]?.jsonPrimitive?.content
    } else {
      // the GitHub api answers with the release tag, e.g. "v1.0.9"
      element.jsonObject[TAG_NAME_KEY]?.jsonPrimitive?.content?.removePrefix("v")
    }
  }

  private companion object {
    const val JSON_FILE_NAME = "update.json"
    const val VERSION_NAME_KEY = "versionName"
    const val TAG_NAME_KEY = "tag_name"
    const val HTTP_OK = 200
    const val CONNECT_TIMEOUT_MS = 5_000
    const val READ_TIMEOUT_MS = 8_000
  }
}
