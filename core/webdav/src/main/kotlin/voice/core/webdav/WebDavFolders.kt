package voice.core.webdav

import androidx.core.net.toUri
import androidx.datastore.core.DataStore
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import voice.core.data.BookId
import voice.core.data.folders.DocumentFileWithUri
import voice.core.data.folders.RemoteBookSources
import voice.core.data.isAudioFile
import voice.core.data.normalizeRemoteUrl
import voice.core.data.toUri
import voice.core.documentfile.CachedDocumentFile
import voice.core.logging.api.Logger

@ContributesBinding(AppScope::class)
@Inject
public class WebDavRemoteBookSources internal constructor(
  @WebDavBookSourcesStore private val sourcesStore: DataStore<List<WebDavBookSource>>,
  private val client: WebDavClient,
  private val resolver: WebDavCredentialResolver,
  private val playbackCache: WebDavPlaybackCache? = null,
) : RemoteBookSources {

  public override fun books(): Flow<List<DocumentFileWithUri>> {
    return sourcesStore.data.map { sources ->
      sources.flatMap { source ->
        expand(source)
      }
    }
  }

  public override suspend fun removeBookRegistration(bookId: BookId): Boolean {
    val uri = bookId.toUri()
    if (uri.scheme !in setOf("http", "https")) return false
    val targetUrl = normalizeRemoteUrl(uri.toString())

    playbackCache?.let { cache ->
      runCatching { cache.removeByPrefix(targetUrl) }
        .onFailure { Logger.w(it, "Could not clear playback cache for $targetUrl") }
    }

    sourcesStore.updateData { sources ->
      sources.mapNotNull { source ->
        val sourceUrl = normalizeRemoteUrl(source.url)
        val matchesSourceUrl = sourceUrl == targetUrl

        when {
          matchesSourceUrl -> {
            // Removing the registered source itself
            null
          }
          source.mode == WebDavBookSource.Mode.LibraryRoot && isCoveredByLibraryRoot(source, targetUrl) -> {
            val updatedLastExpanded = source.lastExpandedUrls.filterNot { normalizeRemoteUrl(it) == targetUrl }
            val excludedSet = source.excludedUrls.map { normalizeRemoteUrl(it) }.toSet()
            val updatedExcluded = if (targetUrl in excludedSet) {
              source.excludedUrls
            } else {
              source.excludedUrls + targetUrl
            }
            source.copy(
              lastExpandedUrls = updatedLastExpanded,
              excludedUrls = updatedExcluded,
            )
          }
          else -> source
        }
      }
    }
    return true
  }

  private fun isCoveredByLibraryRoot(
    source: WebDavBookSource,
    targetUrl: String,
  ): Boolean {
    val sourceUrl = normalizeRemoteUrl(source.url)
    if (targetUrl == sourceUrl || targetUrl.startsWith("$sourceUrl/")) return true
    return source.lastExpandedUrls.any { normalizeRemoteUrl(it) == targetUrl }
  }

  public override suspend fun hasAnyBooks(): Boolean {
    return sourcesStore.data.first().isNotEmpty()
  }

  private suspend fun expand(source: WebDavBookSource): List<DocumentFileWithUri> = withContext(Dispatchers.IO) {
    val excludedSet = source.excludedUrls.map { normalizeRemoteUrl(it) }.toSet()
    when (source.mode) {
      WebDavBookSource.Mode.SingleBook -> {
        if (normalizeRemoteUrl(source.url) in excludedSet) {
          emptyList()
        } else {
          listOf(documentFileWithUri(source.url))
        }
      }
      WebDavBookSource.Mode.LibraryRoot -> expandLibraryRoot(source)
    }
  }

  private suspend fun expandLibraryRoot(source: WebDavBookSource): List<DocumentFileWithUri> {
    val resolved = resolver.byId(source.serverId)
      // the server was deleted: the registration is gone with it
      ?: return emptyList()
    val excludedSet = source.excludedUrls.map { normalizeRemoteUrl(it) }.toSet()
    return try {
      val books = client.list(resolved.server, resolved.password, source.url)
        .mapNotNull { child ->
          val childUrl = normalizeRemoteUrl(child.url)
          if (childUrl in excludedSet) return@mapNotNull null
          val documentFile = documentFile(child.url, child)
          when {
            child.isDirectory -> documentFileWithUri(child.url, child)
            documentFile.isAudioFile() -> documentFileWithUri(child.url, child)
            else -> null
          }
        }
      rememberExpansion(source = source, urls = books.map { it.uri.toString() })
      books
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      // listing failed (e.g. the nas is offline): fall back to the books that
      // were known the last time the listing worked. The shelf then keeps the
      // imported books with their stored metadata instead of deactivating them
      // just because a single request failed.
      Logger.w(e, "Could not expand webdav library root ${source.url}")
      val urls = source.lastExpandedUrls.ifEmpty { listOf(source.url) }
      urls
        .filterNot { normalizeRemoteUrl(it) in excludedSet }
        .map { url -> documentFileWithUri(url) }
    }
  }

  /**
   * Remembers the books a library root expanded into, so a failing listing can
   * fall back to them. Without it a dropped connection would deactivate every
   * imported remote book of that root: the shelves' books are matched by their
   * url, and the scan switches everything it doesn't know to inactive.
   */
  private suspend fun rememberExpansion(
    source: WebDavBookSource,
    urls: List<String>,
  ) {
    val excludedSet = source.excludedUrls.map { normalizeRemoteUrl(it) }.toSet()
    val filteredUrls = urls.filterNot { normalizeRemoteUrl(it) in excludedSet }
    if (source.lastExpandedUrls == filteredUrls) return
    try {
      sourcesStore.updateData { sources ->
        sources.map { stored ->
          if (stored.serverId == source.serverId && stored.url == source.url) {
            stored.copy(lastExpandedUrls = filteredUrls)
          } else {
            stored
          }
        }
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      // remembering is only an optimization for the next scan, so a failing
      // store must not fail the expansion itself
      Logger.w(e, "Could not remember the expansion of ${source.url}")
    }
  }

  private fun documentFile(
    url: String,
    resource: WebDavResource?,
  ): CachedDocumentFile {
    // the url of a directory has to keep its trailing slash: a PROPFIND on the
    // slash-less form ends up as a GET on servers that redirect. Only the id of
    // a book drops it (see `normalizeRemoteUrl`).
    return WebDavDocumentFile(client, resolver, url.toUri(), resource)
  }

  private fun documentFileWithUri(
    url: String,
    resource: WebDavResource? = null,
  ): DocumentFileWithUri {
    val uri = url.toUri()
    return DocumentFileWithUri(
      documentFile = documentFile(url, resource),
      uri = uri,
    )
  }
}
