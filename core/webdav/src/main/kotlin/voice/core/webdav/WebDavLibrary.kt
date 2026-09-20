package voice.core.webdav

import androidx.datastore.core.DataStore
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import voice.core.data.BookId
import voice.core.data.normalizeRemoteUrl
import voice.core.data.repo.RemoteUrlMigration
import voice.core.data.store.CurrentBookStore
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
  private val remoteUrlMigration: RemoteUrlMigration,
  @CurrentBookStore private val currentBookStore: DataStore<BookId?>,
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
    // a failing cache must never take the settings screen down with it
    runCatching { playbackCache.clear() }
      .onFailure { voice.core.logging.api.Logger.w(it, "Could not clear the webdav cache") }
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

    val oldPrefix = existing?.baseUrl
    if (oldPrefix != null && oldPrefix != normalized) {
      // The Room rewrite is deliberately completed before the new server
      // address is published. A failed migration therefore leaves both the
      // server config and all progress pointing at the old, working prefix.
      remoteUrlMigration.migrate(oldPrefix, normalized)
      playbackCache.removeByPrefix(oldPrefix)
      rewriteRegisteredSources(id, oldPrefix, normalized)
      currentBookStore.updateData { current ->
        current?.let { BookId(rewriteUrlPrefix(it.value, oldPrefix, normalized)) }
      }
    }

    serversStore.updateData { servers ->
      servers.filterNot { it.id == id } + server
    }
    resolver.invalidate()
    // Listings are keyed per server: credentials or the url may have changed,
    // so entries cached under this server must not be served anymore.
    client.invalidateListingsForServer(id)
  }

  /** Removes the server and all book registrations that point into it. */
  public suspend fun deleteServer(id: String) {
    val server = server(id)
    serversStore.updateData { servers -> servers.filterNot { it.id == id } }
    sourcesStore.updateData { sources -> sources.filterNot { it.serverId == id } }
    resolver.invalidate()
    client.invalidateListingsForServer(id)
    server?.let {
      runCatching { playbackCache.removeByPrefix(it.baseUrl) }
        .onFailure { e -> voice.core.logging.api.Logger.w(e, "Could not clear the webdav cache prefix") }
    }
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

  /** Servers whose password needs to be re-entered (see [WebDavCredentialResolver.undecryptableServerIds]). */
  public fun undecryptableServerIds(): Set<String> = resolver.undecryptableServerIds()

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
    val normalizedUrl = normalizeRemoteUrl(url)
    sourcesStore.updateData { sources ->
      val updatedSources = sources.map { existing ->
        if (existing.serverId == serverId && existing.mode == WebDavBookSource.Mode.LibraryRoot) {
          val newExcluded = existing.excludedUrls.filterNot { normalizeRemoteUrl(it) == normalizedUrl }
          if (existing.url == normalizedUrl && mode == WebDavBookSource.Mode.LibraryRoot) {
            existing.copy(excludedUrls = emptyList())
          } else {
            existing.copy(excludedUrls = newExcluded)
          }
        } else {
          existing
        }
      }
      val source = WebDavBookSource(
        serverId = serverId,
        url = normalizedUrl,
        displayName = displayName,
        mode = mode,
      )
      updatedSources.filterNot { it.url == source.url && it.serverId == source.serverId } + source
    }
  }

  public suspend fun removeBookSource(source: WebDavBookSource) {
    sourcesStore.updateData { sources -> sources - source }
  }

  private suspend fun rewriteRegisteredSources(
    serverId: String,
    oldPrefix: String,
    newPrefix: String,
  ) {
    sourcesStore.updateData { sources ->
      sources.map { source ->
        if (source.serverId != serverId || !source.url.belongsToPrefix(oldPrefix)) {
          source
        } else {
          source.copy(
            url = rewriteUrlPrefix(source.url, oldPrefix, newPrefix),
            lastExpandedUrls = source.lastExpandedUrls.map { rewriteUrlPrefix(it, oldPrefix, newPrefix) },
          )
        }
      }
    }
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

private fun rewriteUrlPrefix(
  value: String,
  oldPrefix: String,
  newPrefix: String,
): String {
  val old = oldPrefix.trimEnd('/')
  val new = newPrefix.trimEnd('/')
  return when {
    value == old -> new
    value.startsWith("$old/") -> new + value.removePrefix(old)
    else -> value
  }
}

private fun String.belongsToPrefix(prefix: String): Boolean {
  val old = prefix.trimEnd('/')
  return this == old || startsWith("$old/")
}
