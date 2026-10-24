package voice.core.update

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL

/** The latest release as reported by one of the endpoints. */
data class LatestRelease(
  val versionName: String,
  /** The release notes body (markdown), or null when the endpoint has none. */
  val releaseNotes: String?,
)

@Inject
class UpdateChecker {

  private val json = Json {
    ignoreUnknownKeys = true
  }

  // raw.githubusercontent.com first: it reflects main within a minute of a
  // push. jsDelivr follows as fallback because it is reachable from mainland
  // China where github often is not — but it caches branch URLs for up to 12h
  // and its purge API is unreliable, so right after a release it serves stale
  // notes and even a lower version, which the update prompt then offers as a
  // "downgrade". The GitHub api is the last resort; it always answers with the
  // freshly published release and its body. Every endpoint is tried in order
  // and a failure or a timeout of one is silently ignored.
  private val endpoints = listOf(
    "https://raw.githubusercontent.com/cq10086123/Timbre/main/update.json",
    "https://cdn.jsdelivr.net/gh/cq10086123/Timbre@main/update.json",
    "https://fastly.jsdelivr.net/gh/cq10086123/Timbre@main/update.json",
    "https://api.github.com/repos/cq10086123/Timbre/releases/latest",
  )

  /**
   * The latest release, or null when none of the endpoints answered. Null is
   * the normal case for a device without internet access, the caller treats
   * it as "no update".
   */
  suspend fun latestRelease(): LatestRelease? = withContext(Dispatchers.IO) {
    endpoints.firstNotNullOfOrNull { endpoint ->
      runCatching { fetchRelease(endpoint) }.getOrNull()
    }
  }

  private fun fetchRelease(endpoint: String): LatestRelease? {
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
      body?.let { parseRelease(endpoint, it) }
    } finally {
      connection.disconnect()
    }
  }

  internal fun parseRelease(
    endpoint: String,
    body: String,
  ): LatestRelease? {
    val element = json.parseToJsonElement(body).jsonObject
    return if (endpoint.endsWith(JSON_FILE_NAME)) {
      val versionName = element[VERSION_NAME_KEY]?.jsonPrimitive?.contentOrNull ?: return null
      LatestRelease(versionName, element[RELEASE_NOTES_KEY]?.jsonPrimitive?.contentOrNull)
    } else {
      // the GitHub api answers with the release tag, e.g. "v1.0.9", and the
      // markdown release notes in the body field
      val tagName = element[TAG_NAME_KEY]?.jsonPrimitive?.contentOrNull ?: return null
      LatestRelease(tagName.removePrefix("v"), element[BODY_KEY]?.jsonPrimitive?.contentOrNull)
    }
  }

  private companion object {
    const val JSON_FILE_NAME = "update.json"
    const val VERSION_NAME_KEY = "versionName"
    const val RELEASE_NOTES_KEY = "releaseNotes"
    const val TAG_NAME_KEY = "tag_name"
    const val BODY_KEY = "body"
    const val HTTP_OK = 200
    const val CONNECT_TIMEOUT_MS = 5_000
    const val READ_TIMEOUT_MS = 8_000
  }
}
