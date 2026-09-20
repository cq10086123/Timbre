package voice.core.webdav

import androidx.core.net.toUri
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

@RunWith(AndroidJUnit4::class)
class WebDavDocumentFileTest {

  private lateinit var server: MockWebServer
  private val client = WebDavClient()
  private lateinit var serversStore: MemoryDataStore<List<WebDavServer>>
  private lateinit var resolver: WebDavCredentialResolver

  @Before
  fun setUp() {
    server = MockWebServer()
    server.start()
    serversStore = MemoryDataStore(listOf(serverConfig(password = "secret")))
    resolver = WebDavCredentialResolver(serversStore, testWebDavSecrets())
  }

  @After
  fun tearDown() {
    server.close()
  }

  @Test
  fun failedPropsAreRetriedAfterCooldown() = runTest {
    val url = server.url("/dav/01.mp3").toString()
    server.enqueue(error())
    server.enqueue(ok())

    // retryAfterMs = 0 forces an immediate retry instead of waiting out
    // the cooldown, so the test observes the recovery path deterministically
    val file = WebDavDocumentFile(client, resolver, url.toUri(), knownResource = null, retryAfterMs = 0L)
    assertEquals(expected = 0L, actual = file.length())
    assertEquals(expected = 42L, actual = file.length())
    assertEquals(expected = 2, actual = server.requestCount)
  }

  @Test
  fun changedPasswordIsPickedUp() = runTest {
    val url = server.url("/dav/01.mp3").toString()
    server.dispatcher = object : Dispatcher() {
      override fun dispatch(request: RecordedRequest): MockResponse {
        return if (request.headers["Authorization"] == basic("new")) {
          ok()
        } else {
          MockResponse.Builder().code(401).build()
        }
      }
    }

    val file = WebDavDocumentFile(client, resolver, url.toUri(), knownResource = null, retryAfterMs = 0L)
    assertEquals(expected = 0L, actual = file.length())

    // the user re-entered the password, like WebDavLibrary.saveServer does
    serversStore.updateData { listOf(serverConfig(password = "new")) }
    resolver.invalidate()

    assertEquals(expected = 42L, actual = file.length())
  }

  private fun serverConfig(password: String): WebDavServer {
    return WebDavServer(
      id = "1",
      name = "nas",
      baseUrl = server.url("/dav").toString().trimEnd('/'),
      username = "user",
      encryptedPassword = password.reversed(),
      trustAllCertificates = false,
    )
  }

  private fun basic(password: String): String {
    return "Basic " + java.util.Base64.getEncoder()
      .encodeToString("user:$password".toByteArray())
  }

  private fun error(): MockResponse {
    return MockResponse.Builder().code(500).build()
  }

  private fun ok(): MockResponse {
    return MockResponse.Builder()
      .code(207)
      .body(
        """
        <?xml version="1.0"?>
        <d:multistatus xmlns:d="DAV:">
          <d:response>
            <d:href>/dav/01.mp3</d:href>
            <d:propstat><d:prop><d:getcontentlength>42</d:getcontentlength></d:prop></d:propstat>
          </d:response>
        </d:multistatus>
        """.trimIndent(),
      )
      .build()
  }
}
