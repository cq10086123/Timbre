package voice.core.webdav

import android.net.Uri
import androidx.core.net.toUri
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import voice.core.documentfile.CachedDocumentFile
import voice.core.documentfile.DocumentFileSchemeHandler
import voice.core.logging.api.Logger

internal class WebDavDocumentFile(
  private val client: WebDavClient,
  private val resolver: WebDavCredentialResolver,
  override val uri: Uri,
  private val knownResource: WebDavResource?,
) : CachedDocumentFile {

  private val url: String = uri.toString()

  private val resolved: WebDavCredentialResolver.Resolved? by lazy { resolver.byUri(uri) }

  @Volatile
  private var fetchedResource: WebDavResource? = null

  override val children: List<CachedDocumentFile>
    get() {
      val resolved = resolved ?: return emptyList()
      return try {
        runBlocking {
          client.list(resolved.server, resolved.password, url)
        }.map { child ->
          WebDavDocumentFile(client, resolver, child.url.toUri(), child)
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Logger.w(e, "Could not list $url")
        emptyList()
      }
    }

  override val name: String?
    get() = props()?.name

  override val isDirectory: Boolean
    get() = props()?.isDirectory ?: false

  override val isFile: Boolean
    get() = props()?.isDirectory == false

  override val length: Long
    get() = props()?.contentLength?.takeIf { it >= 0 } ?: 0L

  override val lastModified: Long
    get() = props()?.lastModified ?: 0L

  private fun props(): WebDavResource? {
    knownResource?.let { return it }
    fetchedResource?.let { return it }
    val resolved = resolved ?: return null
    val fetched = try {
      runBlocking {
        client.resource(resolved.server, resolved.password, url)
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Logger.w(e, "Could not fetch properties of $url")
      null
    }
    if (fetched != null) {
      fetchedResource = fetched
    }
    return fetched
  }
}

/**
 * Creates [WebDavDocumentFile]s for uris that point into one of the configured
 * servers. Contributed into the scheme handler set of the delegating
 * [voice.core.documentfile.CachedDocumentFileFactory].
 */
@ContributesIntoSet(AppScope::class)
@Inject
public class WebDavSchemeHandler internal constructor(
  private val client: WebDavClient,
  private val resolver: WebDavCredentialResolver,
) : DocumentFileSchemeHandler {

  public override fun supports(uri: Uri): Boolean {
    if (uri.scheme !in setOf("http", "https")) return false
    return resolver.byUri(uri) != null
  }

  public override fun create(uri: Uri): CachedDocumentFile {
    return WebDavDocumentFile(client, resolver, uri, knownResource = null)
  }
}
