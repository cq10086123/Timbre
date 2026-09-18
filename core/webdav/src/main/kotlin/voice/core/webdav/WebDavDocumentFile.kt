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

  // a file whose properties are unknown asks the server exactly once: every
  // access to name/length/isFile would otherwise start another request, and an
  // unreachable server costs a connect timeout each time (a scan over a whole
  // library adds up to minutes of network timeouts)
  @Volatile
  private var fetchFailed: Boolean = false

  /**
   * The reason the last read failed, so the scan can tell an unreachable book
   * (it stays on the shelf) apart from an empty one (it is removed).
   */
  @Volatile
  private var readError: Throwable? = null

  override val error: Throwable? get() = readError

  override val children: List<CachedDocumentFile>
    get() {
      val resolved = resolved ?: return emptyList()
      return try {
        val resources = runBlocking {
          client.list(resolved.server, resolved.password, url)
        }
        readError = null
        resources.map { child ->
          WebDavDocumentFile(client, resolver, child.url.toUri(), child)
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Logger.w(e, "Could not list $url")
        readError = e
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
    if (fetchFailed) return null
    val fetched = try {
      runBlocking {
        client.resource(resolved.server, resolved.password, url)
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Logger.w(e, "Could not fetch properties of $url")
      fetchFailed = true
      readError = e
      return null
    }
    if (fetched == null) {
      // the server answered, but has no such resource: it is gone, not offline
      fetchFailed = true
      return null
    }
    fetchedResource = fetched
    readError = null
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
