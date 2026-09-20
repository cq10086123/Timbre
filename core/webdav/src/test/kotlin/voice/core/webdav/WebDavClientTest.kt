package voice.core.webdav

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class WebDavClientTest {

  private lateinit var server: MockWebServer
  private val client = WebDavClient()

  @Before
  fun setUp() {
    server = MockWebServer()
    server.start()
  }

  @After
  fun tearDown() {
    server.close()
  }

  private fun newServer(trustAll: Boolean = false): WebDavServer {
    return WebDavServer(
      id = "1",
      name = "nas",
      baseUrl = server.url("/dav").toString().trimEnd('/'),
      username = "user",
      encryptedPassword = "",
      trustAllCertificates = trustAll,
    )
  }

  @Test
  fun listSendsPropfindWithBasicAuthAndDepth() = runTest {
    server.enqueue(
      MockResponse.Builder()
        .code(207)
        .body(propfindBody())
        .build(),
    )
    val serverConfig = newServer()

    val resources = client.list(serverConfig, "secret", url = server.url("/dav").toString())

    assertEquals(expected = 2, actual = resources.size)
    val recorded = server.takeRequest()
    assertEquals(expected = "PROPFIND", actual = recorded.method)
    assertEquals(expected = "1", actual = recorded.headers["Depth"])
    assertEquals(
      expected = "Basic ${java.util.Base64.getEncoder().encodeToString("user:secret".toByteArray())}",
      actual = recorded.headers["Authorization"],
    )
  }

  @Test
  fun listCachesTheDirectory() = runTest {
    server.enqueue(
      MockResponse.Builder()
        .code(207)
        .body(propfindBody())
        .build(),
    )
    val serverConfig = newServer()
    val url = server.url("/dav").toString()

    client.list(serverConfig, "secret", url).let { check(it.isNotEmpty()) }
    val second = client.list(serverConfig, "secret", url)
    check(second.size == 2)

    assertEquals(expected = 2, actual = second.size)
    assertEquals(expected = 1, actual = server.requestCount)
  }

  @Test
  fun listingsAreScopedPerServer() = runTest {
    repeat(2) {
      server.enqueue(
        MockResponse.Builder()
          .code(207)
          .body(propfindBody())
          .build(),
      )
    }
    val url = server.url("/dav").toString()

    client.list(newServer().copy(id = "one"), "secret", url).let { check(it.isNotEmpty()) }
    client.list(newServer().copy(id = "two"), "secret", url).let { check(it.isNotEmpty()) }

    // the same url on another server (e.g. after re-adding a nas) must not
    // be served from the other server's listing
    assertEquals(expected = 2, actual = server.requestCount)
  }

  @Test
  fun listInvalidationForcesARefetch() = runTest {
    repeat(2) {
      server.enqueue(
        MockResponse.Builder()
          .code(207)
          .body(propfindBody())
          .build(),
      )
    }
    val serverConfig = newServer()
    val url = server.url("/dav").toString()

    client.list(serverConfig, "secret", url).let { check(it.isNotEmpty()) }
    client.invalidateListingsForServer(serverConfig.id)
    client.list(serverConfig, "secret", url).let { check(it.isNotEmpty()) }

    assertEquals(expected = 2, actual = server.requestCount)
  }

  @Test
  fun listingCacheIsBounded() = runTest {
    server.dispatcher = object : Dispatcher() {
      override fun dispatch(request: RecordedRequest): MockResponse {
        return MockResponse.Builder()
          .code(207)
          .body(propfindBody())
          .build()
      }
    }
    val serverConfig = newServer()
    val total = 300
    repeat(total) { index ->
      client.list(serverConfig, "secret", server.url("/dav/dir$index/").toString())
        .let { check(it.isNotEmpty()) }
    }
    val afterFirstPass = server.requestCount

    // with an unbounded cache this second pass would be fully served from
    // memory; a bounded cache must have evicted and refetch instead
    repeat(total) { index ->
      client.list(serverConfig, "secret", server.url("/dav/dir$index/").toString())
        .let { check(it.isNotEmpty()) }
    }

    assertTrue(server.requestCount > afterFirstPass)
  }

  @Test
  fun listRetriesWithATrailingSlash() = runTest {
    // servers that only answer a PROPFIND in the trailing slash form of a
    // collection hand out something that is no multistatus for the slash-less
    // form (the http client follows their redirect as a GET)
    server.enqueue(
      MockResponse.Builder()
        .code(200)
        .body("<html>directory listing</html>")
        .build(),
    )
    server.enqueue(
      MockResponse.Builder()
        .code(207)
        .body(propfindBody())
        .build(),
    )

    val resources = client.list(newServer(), "secret", url = server.url("/dav").toString())

    assertEquals(expected = 2, actual = resources.size)
    assertEquals(expected = 2, actual = server.requestCount)
  }

  @Test
  fun propfindUnauthorizedThrowsAuthException() = runTest {
    server.enqueue(
      MockResponse.Builder()
        .code(401)
        .build(),
    )

    kotlin.test.assertFailsWith<WebDavException.Auth> {
      client.list(newServer(), "wrong", url = server.url("/dav").toString())
    }
  }

  @Test
  fun digestChallengeIsRetriedWithDigestAuthorization() = runTest {
    val nonce = "fixed-nonce"
    server.dispatcher = object : Dispatcher() {
      override fun dispatch(request: RecordedRequest): MockResponse {
        val authorization = request.headers["Authorization"].orEmpty()
        return if (authorization.startsWith("Digest ")) {
          MockResponse.Builder()
            .code(207)
            .body(propfindBody())
            .build()
        } else {
          MockResponse.Builder()
            .code(401)
            .setHeader(
              "WWW-Authenticate",
              "Digest realm=\"dav\", nonce=\"$nonce\", qop=\"auth\", algorithm=MD5",
            )
            .build()
        }
      }
    }

    val resources = client.list(newServer(), "secret", url = server.url("/dav").toString())

    assertEquals(expected = 2, actual = server.requestCount)
    assertEquals(expected = 2, actual = resources.size)
    val requests = listOf(server.takeRequest(), server.takeRequest())
    val digestHeader = requests.asSequence()
      .mapNotNull { it.headers["Authorization"] }
      .firstOrNull { it.startsWith("Digest ") }
    assertTrue(digestHeader != null)
    assertTrue(digestHeader.contains("username=\"user\""))
    assertTrue(digestHeader.contains("nonce=\"$nonce\""))
  }

  @Test
  fun probeReportsSupportedRange() = runTest {
    server.enqueue(
      MockResponse.Builder()
        .code(207)
        .body(propfindBody())
        .build(),
    )
    server.enqueue(
      MockResponse.Builder()
        .code(206)
        .body("x")
        .build(),
    )

    val result = client.probe(newServer(), "secret", url = server.url("/dav").toString())

    assertTrue(result is WebDavProbeResult.Success && result.rangeSupported)
    val propfindRequest = server.takeRequest()
    assertEquals(expected = "PROPFIND", actual = propfindRequest.method)
    val rangeRequest = server.takeRequest()
    assertEquals(expected = "bytes=0-0", actual = rangeRequest.headers["Range"])
  }

  @Test
  fun probeReportsAuthErrors() = runTest {
    server.enqueue(
      MockResponse.Builder()
        .code(401)
        .build(),
    )

    val result = client.probe(newServer(), "wrong", url = server.url("/dav").toString())

    assertEquals(expected = WebDavProbeResult.AuthError, actual = result)
  }

  private fun propfindBody(): String {
    return """
      <?xml version="1.0"?>
      <d:multistatus xmlns:d="DAV:">
        <d:response>
          <d:href>/dav/</d:href>
          <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat>
        </d:response>
        <d:response>
          <d:href>/dav/book/</d:href>
          <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat>
        </d:response>
        <d:response>
          <d:href>/dav/01.mp3</d:href>
          <d:propstat><d:prop>
            <d:getcontentlength>42</d:getcontentlength>
          </d:prop></d:propstat>
        </d:response>
      </d:multistatus>
    """.trimIndent()
  }
}
