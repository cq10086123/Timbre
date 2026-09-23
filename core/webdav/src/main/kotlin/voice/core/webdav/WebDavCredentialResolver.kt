package voice.core.webdav

import android.net.Uri
import android.os.SystemClock
import androidx.core.net.toUri
import androidx.datastore.core.DataStore
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Resolves the credentials of a configured server for a given uri. Reads of
 * the store are cached and kept warm in the background because data sources
 * resolve on every open, which happens on loader threads.
 */
@SingleIn(AppScope::class)
@Inject
public class WebDavCredentialResolver(
  @WebDavServersStore private val serversStore: DataStore<List<WebDavServer>>,
  private val secrets: WebDavSecrets,
  scope: CoroutineScope,
) {

  public data class Resolved(
    val server: WebDavServer,
    val password: String,
  )

  private val lock = Any()
  private var cache: Pair<Long, List<Resolved>>? = null
  private var allServers: List<WebDavServer> = emptyList()

  init {
    // Keep the snapshot warm so playback/loader threads rarely hit runBlocking.
    scope.launch {
      serversStore.data.collect { servers ->
        replaceCache(servers)
      }
    }
  }

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
    // Drop the TTL window so the next lookup re-decrypts (e.g. after a password
    // re-entry). The background collector will also refresh when the store
    // emits; this covers the gap before that emission is observed.
    synchronized(lock) {
      cache = null
    }
  }

  /**
   * Servers whose stored password cannot be decrypted, e.g. because the app
   * was reinstalled and the keystore key is gone. They stay out of
   * [byUri]/[byId] until the user re-enters the password.
   */
  public fun undecryptableServerIds(): Set<String> = synchronized(lock) {
    val servers = allServersIfKnown()
    val usable = snapshot().mapTo(mutableSetOf()) { it.server.id }
    servers
      .filter { it.id !in usable }
      .map { it.id }
      .toSet()
  }

  private fun replaceCache(servers: List<WebDavServer>) {
    val resolved = servers.mapNotNull { server ->
      secrets.decrypt(server.encryptedPassword)?.let { Resolved(server, it) }
    }
    synchronized(lock) {
      allServers = servers
      cache = SystemClock.elapsedRealtime() to resolved
    }
  }

  private fun allServersIfKnown(): List<WebDavServer> {
    if (allServers.isNotEmpty() || cache != null) {
      return allServers
    }
    // Cold path before the background collector lands (or an empty store).
    return runBlocking { serversStore.data.first() }.also { allServers = it }
  }

  private fun snapshot(): List<Resolved> {
    val now = SystemClock.elapsedRealtime()
    val cached = cache
    if (cached != null && now - cached.first < TTL_MS) {
      return cached.second
    }
    val servers = runBlocking { serversStore.data.first() }
    val resolved = servers.mapNotNull { server ->
      secrets.decrypt(server.encryptedPassword)?.let { Resolved(server, it) }
    }
    allServers = servers
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
