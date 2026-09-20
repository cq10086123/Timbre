package voice.core.webdav

import android.net.Uri
import android.os.SystemClock
import androidx.annotation.WorkerThread
import androidx.core.net.toUri
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CancellationException
import voice.core.documentfile.CachedDocumentFile
import voice.core.documentfile.DocumentFileSchemeHandler
import voice.core.logging.api.Logger

@WorkerThread
internal class WebDavDocumentFile(
  private val client: WebDavClient,
  private val resolver: WebDavCredentialResolver,
  override val uri: Uri,
  private val knownResource: WebDavResource?,
  private val retryAfterMs: Long = DEFAULT_RETRY_AFTER_MS,
) : CachedDocumentFile {

  private val url: String = uri.toString()

  @Volatile
  private var fetchedResource: WebDavResource? = null

  // a file whose properties are unknown asks the server at most once per
  // cooldown: every access to name/length/isFile would otherwise start another
  // request, and an unreachable server costs a connect timeout each time (a
  // scan over a whole library adds up to minutes of network timeouts). But the
  // failure must not latch forever: a nas hiccup at the start of a long scan
  // would otherwise misjudge the book for the rest of the scan, and a
  // password change would never be picked up by this instance.
  @Volatile
  private var lastFetchFailureAt: Long = 0L

  /**
   * The reason the last read failed, so the scan can tell an unreachable book
   * (it stays on the shelf) apart from an empty one (it is removed).
   */
  @Volatile
  private var readError: Throwable? = null

  override val error: Throwable? get() = readError

  override suspend fun children(): List<CachedDocumentFile> {
    // resolve on every access (the resolver caches its snapshot), same as props()
    val resolved = resolver.byUri(uri) ?: return emptyList()
    return try {
      val resources = client.list(resolved.server, resolved.password, url)
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

  override suspend fun name(): String? = props()?.name

  override suspend fun isDirectory(): Boolean = props()?.isDirectory ?: false

  override suspend fun isFile(): Boolean = props()?.isDirectory == false

  override suspend fun length(): Long = props()?.contentLength?.takeIf { it >= 0 } ?: 0L

  override suspend fun lastModified(): Long = props()?.lastModified ?: 0L

  private suspend fun props(): WebDavResource? {
    knownResource?.let { return it }
    fetchedResource?.let { return it }
    // resolve on every access (the resolver caches its snapshot): a lazily
    // cached resolution would keep using a stale password after it changed
    val resolved = resolver.byUri(uri) ?: return null
    if (SystemClock.elapsedRealtime() - lastFetchFailureAt < retryAfterMs) return null
    val fetched = try {
      client.resource(resolved.server, resolved.password, url)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Logger.w(e, "Could not fetch properties of $url")
      lastFetchFailureAt = SystemClock.elapsedRealtime()
      readError = e
      return null
    }
    if (fetched == null) {
      // the server answered, but has no such resource: it is gone, not offline
      lastFetchFailureAt = SystemClock.elapsedRealtime()
      return null
    }
    fetchedResource = fetched
    lastFetchFailureAt = 0L
    readError = null
    return fetched
  }

  private companion object {
    const val DEFAULT_RETRY_AFTER_MS = 60_000L
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
