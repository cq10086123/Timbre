package voice.core.webdav

import androidx.datastore.core.DataStore
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.util.UUID

/**
 * Facade for the server management and remote browsing ui.
 */
@SingleIn(AppScope::class)
@Inject
public class WebDavLibrary internal constructor(
  @WebDavServersStore private val serversStore: DataStore<List<WebDavServer>>,
  @WebDavBookSourcesStore private val sourcesStore: DataStore<List<WebDavBookSource>>,
  private val secrets: WebDavSecrets,
  private val client: WebDavClient,
  private val resolver: WebDavCredentialResolver,
  private val playbackCache: WebDavPlaybackCache,
) {

  public fun servers(): Flow<List<WebDavServer>> {
    return serversStore.data
  }

  public fun bookSources(): Flow<List<WebDavBookSource>> {
    return sourcesStore.data
  }

  /** Total size of the playback cache in bytes. */
  public fun cachedBytes(): Long {
    return playbackCache.cachedBytes()
  }

  public suspend fun clearCache() {
    playbackCache.clear()
  }

  public suspend fun server(serverId: String): WebDavServer? {
    return serversStore.data.first().firstOrNull { it.id == serverId }
  }

  /**
   * Creates or updates a server. [password] of null or blank keeps the stored
   * password of an existing server.
   */
  public suspend fun saveServer(
    name: String,
    baseUrl: String,
    username: String,
    password: String?,
    trustAllCertificates: Boolean,
    existingId: String? = null,
  ) {
    val normalized = normalizeBaseUrl(baseUrl)
    val id = existingId ?: UUID.randomUUID().toString()
    val existing = serversStore.data.first().firstOrNull { it.id == id }
    val encryptedPassword = if (password.isNullOrBlank()) {
      existing?.encryptedPassword
        ?: throw IllegalArgumentException("A password is required for a new server")
    } else {
      secrets.encrypt(password)
    }
    val server = WebDavServer(
      id = id,
      name = name.ifBlank { normalized },
      baseUrl = normalized,
      username = username,
      encryptedPassword = encryptedPassword,
      trustAllCertificates = trustAllCertificates,
    )
    serversStore.updateData { servers ->
      servers.filterNot { it.id == id } + server
    }
    resolver.invalidate()
  }

  /** Removes the server and all book registrations that point into it. */
  public suspend fun deleteServer(id: String) {
    val server = server(id)
    serversStore.updateData { servers -> servers.filterNot { it.id == id } }
    sourcesStore.updateData { sources -> sources.filterNot { it.serverId == id } }
    resolver.invalidate()
    server?.let { playbackCache.removeByPrefix(it.baseUrl) }
  }

  public suspend fun testConnection(
    baseUrl: String,
    username: String,
    password: String,
    trustAllCertificates: Boolean,
  ): WebDavProbeResult {
    val normalized = normalizeBaseUrl(baseUrl)
    val probeServer = WebDavServer(
      id = "probe",
      name = "probe",
      baseUrl = normalized,
      username = username,
      encryptedPassword = "",
      trustAllCertificates = trustAllCertificates,
    )
    return client.probe(probeServer, password, normalized)
  }

  public suspend fun list(
    serverId: String,
    url: String,
  ): List<WebDavResource> {
    val resolved = resolver.byId(serverId) ?: throw WebDavException.Auth(url)
    return client.list(resolved.server, resolved.password, url)
  }

  public suspend fun addBook(
    serverId: String,
    url: String,
    displayName: String,
    mode: WebDavBookSource.Mode,
  ) {
    sourcesStore.updateData { sources ->
      val source = WebDavBookSource(
        serverId = serverId,
        url = url.trimEnd('/'),
        displayName = displayName,
        mode = mode,
      )
      sources.filterNot { it.url == source.url && it.serverId == source.serverId } + source
    }
  }

  public suspend fun removeBookSource(source: WebDavBookSource) {
    sourcesStore.updateData { sources -> sources - source }
  }

  public companion object {

    public fun normalizeBaseUrl(baseUrl: String): String {
      val trimmed = baseUrl.trim().trimEnd('/')
      if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
        throw IllegalArgumentException("WebDAV address must start with http:// or https://")
      }
      return trimmed
    }
  }
}
