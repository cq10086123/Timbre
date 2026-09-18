package voice.core.webdav

import android.net.Uri
import androidx.datastore.core.DataStore
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import voice.core.data.BookId
import voice.core.data.isAudioFile
import voice.core.data.folders.DocumentFileWithUri
import voice.core.data.folders.RemoteBookSources
import voice.core.data.toUri
import voice.core.documentfile.CachedDocumentFile

@ContributesBinding(AppScope::class)
@Inject
public class WebDavRemoteBookSources internal constructor(
  @WebDavBookSourcesStore private val sourcesStore: DataStore<List<WebDavBookSource>>,
  private val client: WebDavClient,
  private val resolver: WebDavCredentialResolver,
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
    val normalized = uri.toString().trimEnd('/')
    val matching = sourcesStore.data.first()
      .filter { it.url.trimEnd('/') == normalized }
    if (matching.isEmpty()) return false
    sourcesStore.updateData { sources -> sources.filterNot { it in matching } }
    return true
  }

  public override suspend fun hasAnyBooks(): Boolean {
    return sourcesStore.data.first().isNotEmpty()
  }

  private suspend fun expand(source: WebDavBookSource): List<DocumentFileWithUri> =
    withContext(Dispatchers.IO) {
      when (source.mode) {
        WebDavBookSource.Mode.SingleBook -> listOf(source.toDocumentFileWithUri(source.url))

        WebDavBookSource.Mode.LibraryRoot -> {
          val resolved = resolver.byId(source.serverId)
          if (resolved == null) {
            // the server was deleted: the registration is gone with it
            return@withContext emptyList()
          }
          try {
            client.list(resolved.server, resolved.password, source.url)
              .mapNotNull { child ->
                val documentFile = documentFile(child.url, child)
                when {
                  child.isDirectory -> source.toDocumentFileWithUri(child.url)

                  documentFile.isAudioFile() -> source.toDocumentFileWithUri(child.url)

                  else -> null
                }
              }
          } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            // listing failed (e.g. offline): keep the registration itself so the
            // book stays on the shelf with its stored metadata instead of
            // disappearing just because the nas is unreachable
            voice.core.logging.api.Logger.w(e, "Could not expand webdav library root ${source.url}")
            listOf(source.toDocumentFileWithUri(source.url))
          }
        }
      }
    }

  private fun documentFile(
    url: String,
    resource: WebDavResource?,
  ): CachedDocumentFile {
    return WebDavDocumentFile(client, resolver, Uri.parse(url), resource)
  }

  private fun WebDavBookSource.toDocumentFileWithUri(url: String): DocumentFileWithUri {
    val uri = Uri.parse(url)
    return DocumentFileWithUri(
      documentFile = documentFile(url, null),
      uri = uri,
    )
  }
}
