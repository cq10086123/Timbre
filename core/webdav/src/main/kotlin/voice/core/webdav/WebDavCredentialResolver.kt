package voice.core.webdav

import android.net.Uri
import android.os.SystemClock
import androidx.core.net.toUri
import androidx.datastore.core.DataStore
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Resolves the credentials of a configured server for a given uri. Reads of
 * the store are cached for a short time because data sources resolve on every
 * open, which happens on loader threads.
 */
@SingleIn(AppScope::class)
@Inject
public class WebDavCredentialResolver(
  @WebDavServersStore private val serversStore: DataStore<List<WebDavServer>>,
  private val secrets: WebDavSecrets,
) {

  public data class Resolved(
    val server: WebDavServer,
    val password: String,
  )

  private val lock = Any()
  private var cache: Pair<Long, List<Resolved>>? = null

  public fun byUri(uri: Uri): Resolved? {
    return synchronized(lock) {
      snapshot().firstOrNull { it.server.matches(uri) }
    }
  }

  public fun byId(id: String): Resolved? {
    return synchronized(lock) {
      snapshot().firstOrNull { it.server.id == id }
    }
  }

  public fun invalidate() {
    synchronized(lock) {
      cache = null
    }
  }

  private fun snapshot(): List<Resolved> {
    val now = SystemClock.elapsedRealtime()
    val cached = cache
    if (cached != null && now - cached.first < TTL_MS) {
      return cached.second
    }
    val resolved = runBlocking { serversStore.data.first() }
      .mapNotNull { server ->
        secrets.decrypt(server.encryptedPassword)?.let { Resolved(server, it) }
      }
    cache = now to resolved
    return resolved
  }

  private companion object {
    const val TTL_MS = 10_000L
  }
}

/** Whether [uri] points into this server (scheme, host, port and path prefix). */
public fun WebDavServer.matches(uri: android.net.Uri): Boolean {
  if (uri.scheme?.lowercase() !in setOf("http", "https")) return false
  val base = baseUrl.toUri()
  if (!uri.scheme.equals(base.scheme, ignoreCase = true)) return false
  if (!uri.host.equals(base.host, ignoreCase = true)) return false
  if (base.effectivePort() != uri.effectivePort()) return false
  val basePath = base.path?.trimEnd('/') ?: ""
  val uriPath = uri.path ?: return false
  return uriPath == basePath || uriPath.startsWith("$basePath/")
}

private fun Uri.effectivePort(): Int {
  if (port != -1) return port
  return when (scheme?.lowercase()) {
    "http" -> 80
    "https" -> 443
    else -> -1
  }
}
