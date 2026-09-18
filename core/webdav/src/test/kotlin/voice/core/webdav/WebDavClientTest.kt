package voice.core.webdav

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

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

    client.list(serverConfig, "secret", url)
    val second = client.list(serverConfig, "secret", url)

    assertEquals(expected = 2, actual = second.size)
    assertEquals(expected = 1, actual = server.requestCount)
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
