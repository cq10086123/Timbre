package voice.core.webdav

import android.annotation.SuppressLint
import android.util.Base64
import androidx.core.net.toUri
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import voice.core.logging.api.Logger
import java.io.IOException
import java.io.StringReader
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

public sealed class WebDavProbeResult {
  /** The server is reachable and authenticated. [rangeSupported] reports GET Range support. */
  public class Success(public val rangeSupported: Boolean) : WebDavProbeResult()

  public data object AuthError : WebDavProbeResult()

  public data object CertificateError : WebDavProbeResult()

  public data object NetworkError : WebDavProbeResult()

  public class Other(public val message: String) : WebDavProbeResult()
}

@SingleIn(AppScope::class)
@Inject
public class WebDavClient {

  private val plainClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
      .connectTimeout(10, TimeUnit.SECONDS)
      .readTimeout(30, TimeUnit.SECONDS)
      .build()
  }

  // Opt-in per server for self-signed certificates; the user explicitly
  // enables this in the server settings, so a lint suppression is intended.
  private val trustAllClient: OkHttpClient by lazy {
    val trustManager =
      @SuppressLint("CustomX509TrustManager")
      object : X509TrustManager {
        override fun checkClientTrusted(
          chain: Array<X509Certificate>,
          authType: String,
        ) = Unit

        override fun checkServerTrusted(
          chain: Array<X509Certificate>,
          authType: String,
        ) = Unit

        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
      }
    val sslContext = SSLContext.getInstance("TLS")
    sslContext.init(null, arrayOf<TrustManager>(trustManager), SecureRandom())
    plainClient.newBuilder()
      .sslSocketFactory(sslContext.socketFactory, trustManager)
      .hostnameVerifier { _, _ -> true }
      .build()
  }

  /** Directory listings with a short ttl so a scan walking a tree reuses one request per directory. */
  private val listingCache = ConcurrentHashMap<String, Pair<Long, List<WebDavResource>>>()

  /**
   * Returns a client configured for one server. A fresh authenticator-bearing
   * client is cheap (it shares OkHttp's connection pool with the base client)
   * and keeps credentials scoped to the server that is being accessed.
   */
  public fun clientFor(
    server: WebDavServer,
    password: String,
  ): OkHttpClient {
    val base = if (server.trustAllCertificates) trustAllClient else plainClient
    return base.newBuilder()
      .authenticator(WebDavDigestAuthenticator(server.username, password))
      .build()
  }

  /** Client without an authentication challenge handler, retained for callers that only need TLS setup. */
  public fun clientFor(server: WebDavServer): OkHttpClient {
    return if (server.trustAllCertificates) trustAllClient else plainClient
  }

  public fun basicAuth(
    username: String,
    password: String,
  ): String {
    val credentials = "$username:$password"
    return "Basic " + Base64.encodeToString(credentials.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
  }

  public fun authHeader(
    server: WebDavServer,
    password: String,
  ): String = basicAuth(server.username, password)

  /** Lists the children of [url]. The directory itself is not part of the result. */
  public suspend fun list(
    server: WebDavServer,
    password: String,
    url: String,
  ): List<WebDavResource> {
    val cached = listingCache[url]
    if (cached != null && System.currentTimeMillis() - cached.first < LISTING_TTL_MS) {
      return cached.second
    }
    val resources = propfind(server, password, url, depth = 1)
      .filterNot { it.url == url || it.url == "$url/" }
    listingCache[url] = System.currentTimeMillis() to resources
    return resources
  }

  /** Fetches the properties of a single resource. */
  public suspend fun resource(
    server: WebDavServer,
    password: String,
    url: String,
  ): WebDavResource? {
    // the listing cache holds the CHILDREN of a collection (depth 1, without
    // the collection itself), so it cannot answer a depth 0 lookup of the
    // resource itself
    return propfind(server, password, url, depth = 0).firstOrNull()
  }

  public suspend fun probe(
    server: WebDavServer,
    password: String,
    url: String,
  ): WebDavProbeResult {
    return try {
      val resources = propfind(server, password, url, depth = 1)
      val file = resources.firstOrNull { !it.isDirectory }
      val rangeSupported = if (file != null) {
        rangeProbe(server, password, file.url)
      } else {
        true
      }
      WebDavProbeResult.Success(rangeSupported)
    } catch (e: WebDavException.Auth) {
      WebDavProbeResult.AuthError
    } catch (e: WebDavException.NotFound) {
      WebDavProbeResult.Other("404 $url")
    } catch (e: WebDavException) {
      WebDavProbeResult.Other(e.message ?: "error")
    } catch (e: SSLException) {
      Logger.w(e, "WebDav probe certificate error")
      WebDavProbeResult.CertificateError
    } catch (e: IOException) {
      Logger.w(e, "WebDav probe network error")
      WebDavProbeResult.NetworkError
    }
  }

  private suspend fun rangeProbe(
    server: WebDavServer,
    password: String,
    fileUrl: String,
  ): Boolean {
    return try {
      val request = Request.Builder()
        .url(fileUrl)
        .header("Authorization", authHeader(server, password))
        .header("Range", "bytes=0-0")
        .build()
      clientFor(server, password).await(request).use { response ->
        response.code == 206 || response.code == 200
      }
    } catch (e: Exception) {
      if (e is SSLException || findCause<SSLException>(e) != null) throw e
      Logger.w(e, "WebDav range probe failed for $fileUrl")
      true
    }
  }

  private suspend fun propfind(
    server: WebDavServer,
    password: String,
    url: String,
    depth: Int,
  ): List<WebDavResource> {
    return propfindOnce(server, password, url, depth).fold(
      onSuccess = { it },
      onFailure = { error ->
        if (error is CancellationException) throw error
        // Only for a few responses it is worth asking again in the trailing
        // slash form of the url: an offline server (or a rejected password)
        // fails the same way twice and would only double the timeout. Book ids
        // drop the trailing slash of a collection (see `normalizeRemoteUrl`),
        // so the request is repeated before the resource is given up.
        if (!error.isWorthRetryingWithSlash() || url.endsWith("/")) throw error
        Logger.d("Retrying the propfind of $url with a trailing slash: $error")
        propfindOnce(server, password, "$url/", depth).getOrElse { retryError ->
          if (retryError is CancellationException) throw retryError
          throw retryError
        }
      },
    )
  }

  private suspend fun propfindOnce(
    server: WebDavServer,
    password: String,
    url: String,
    depth: Int,
  ): Result<List<WebDavResource>> {
    return try {
      Result.success(propfindRequest(server, password, url, depth))
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Result.failure(e)
    }
  }

  /**
   * A server that only serves a collection in its trailing slash form answers
   * the slash-less form with a redirect or with a 404. The http client follows
   * the redirect of the collection as a GET, so what arrives here is a plain
   * `200` (with a body that is no multistatus) instead of a `207`.
   */
  private fun Throwable.isWorthRetryingWithSlash(): Boolean {
    return when (this) {
      is WebDavException.NotFound -> true
      is WebDavException.Http -> code == 200 || code == 404 || code == 405
      else -> false
    }
  }

  private suspend fun propfindRequest(
    server: WebDavServer,
    password: String,
    url: String,
    depth: Int,
  ): List<WebDavResource> = withContext(Dispatchers.IO) {
    val request = Request.Builder()
      .url(url)
      .header("Authorization", authHeader(server, password))
      .header("Depth", depth.toString())
      .method("PROPFIND", null)
      .build()
    clientFor(server, password).await(request).use { response ->
      when (response.code) {
        207 -> {
          val body = response.body.string()
          MultiStatusParser.parse(
            StringReader(body),
            baseUrl = url,
            rootUrl = rootUrl(url),
            skipSelf = depth == 1,
          )
        }
        401, 403 -> throw WebDavException.Auth(url)
        404 -> throw WebDavException.NotFound(url)
        else -> throw WebDavException.Http(response.code, url)
      }
    }
  }

  /** The origin of [url]: `scheme://host[:port]`. */
  private fun rootUrl(url: String): String {
    val uri = url.toUri()
    val authority = uri.encodedAuthority ?: return url
    return "${uri.scheme}://$authority"
  }

  private inline fun <reified T : Throwable> findCause(throwable: Throwable): T? {
    var current: Throwable? = throwable
    while (current != null) {
      if (current is T) return current
      current = current.cause
    }
    return null
  }

  private suspend fun OkHttpClient.await(request: Request): Response = suspendCancellableCoroutine { continuation ->
    val call = newCall(request)
    continuation.invokeOnCancellation { call.cancel() }
    call.enqueue(
      object : Callback {
        override fun onFailure(
          call: Call,
          e: IOException,
        ) {
          if (continuation.isActive) continuation.resumeWithException(e)
        }

        override fun onResponse(
          call: Call,
          response: Response,
        ) {
          if (continuation.isActive) continuation.resume(response) else response.close()
        }
      },
    )
  }

  private companion object {
    const val LISTING_TTL_MS = 30_000L
  }
}
