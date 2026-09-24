package voice.core.online

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OnlineSourceClientTest {

  private lateinit var server: MockWebServer
  private lateinit var client: OnlineSourceClient

  private val svgCaptcha =
    "<svg><text x=\"1\" y=\"2\">T</text><text x=\"3\" y=\"4\">N</text>" +
      "<text x=\"5\" y=\"6\">4</text><text x=\"7\" y=\"8\">2</text></svg>"

  @Before
  fun setUp() {
    server = MockWebServer()
    server.start()
    client = OnlineSourceClient.create(OkHttpClient())
  }

  @After
  fun tearDown() {
    server.close()
  }

  private fun baseUrl(): String = server.url("/").toString().trimEnd('/')

  private fun ok(body: String): MockResponse = MockResponse.Builder().code(200).body(body).build()

  private fun b64(value: String): String = java.util.Base64.getEncoder().encodeToString(value.toByteArray())

  @Test
  fun loginSolvesTheCaptchaAndReturnsTheToken() = runTest {
    server.enqueue(ok("""{"captchaId":"cid-1","svg":"data:image/svg+xml;base64,${b64(svgCaptcha)}"}"""))
    server.enqueue(ok("""{"success":true,"token":"tok-1"}"""))

    val token = client.login(baseUrl(), credential = "XM-TEST-CARD")

    assertEquals(expected = "tok-1", actual = token)
    server.takeRequest() // captcha GET
    val login = server.takeRequest() // login POST
    assertEquals(expected = "/api/auth/login", actual = login.url.encodedPath)
    val body = login.body?.utf8().orEmpty()
    assertTrue(body.contains(""""code":"XM-TEST-CARD""""))
    assertTrue(body.contains(""""captchaId":"cid-1""""))
    assertTrue(body.contains(""""captcha":"TN42""""))
  }

  @Test
  fun interfacesExcludeOfficialAndDisabledSources() = runTest {
    server.enqueue(
      ok(
        """{"success":true,"interfaces":[
          {"name":"official","displayName":"官方源","enabled":true},
          {"name":"A","displayName":"接口 A","enabled":true},
          {"name":"B","enabled":false},
          {"name":" Official ","enabled":true},
          {"name":"C","displayName":"接口 C"}
        ]}""",
      ),
    )

    val sources = client.interfaces(baseUrl(), token = "t")

    assertEquals(
      expected = listOf(
        OnlineSourceInfo(name = "A", displayName = "接口 A"),
        OnlineSourceInfo(name = "C", displayName = "接口 C"),
      ),
      actual = sources,
    )
    val request = server.takeRequest()
    assertEquals(expected = "/api/interfaces", actual = request.url.encodedPath)
    assertEquals(expected = "Bearer t", actual = request.headers["Authorization"])
  }

  @Test
  fun interfacesReturnEmptyWhenOnlyOfficialIsAdvertised() = runTest {
    server.enqueue(ok("""{"success":true,"interfaces":[{"name":"official"}]}"""))

    assertTrue(client.interfaces(baseUrl(), token = "t").isEmpty())
  }

  @Test
  fun interfaceSearchKeepsStringBookIds() = runTest {
    server.enqueue(
      ok("""{"success":true,"results":[{"albumId":"ujLrCd","title":"T2","trackCount":0,"cover":"c.gif"}]}"""),
    )

    val results = client.searchSource(baseUrl(), token = "t", source = "A", keyword = "k")

    assertEquals(expected = 1, actual = results.size)
    assertEquals(expected = "A", actual = results[0].source)
    assertEquals(expected = "ujLrCd", actual = results[0].bookId)
  }

  @Test
  fun interfaceAudioReturnsUrlOrNull() = runTest {
    server.enqueue(ok("""{"success":true,"url":"https://stream/x?sign=a"}"""))
    val url = client.sourceAudio(baseUrl(), "t", source = "B", bookId = "8117897634", chapterId = "56248212")
    assertEquals(expected = "https://stream/x?sign=a", actual = url)

    server.enqueue(ok("""{"success":false,"error":"no link"}"""))
    val none = client.sourceAudio(baseUrl(), "t", source = "B", bookId = "1", chapterId = "2")
    assertEquals(expected = null, actual = none)
  }

  @Test
  fun emptyDurationFieldsParseAsZero() = runTest {
    server.enqueue(
      ok(
        """{"success":true,"tracks":[{"trackId":"t1","title":"c1","duration":"","order":1},
           {"trackId":"t2","title":"c2","duration":null,"order":""}]}""",
      ),
    )

    val chapters = client.sourceAlbumList(baseUrl(), token = "t", source = "B", bookId = "8117897634")

    assertEquals(expected = 2, actual = chapters.size)
    assertEquals(expected = 0, actual = chapters[0].durationSeconds)
    assertEquals(expected = 1, actual = chapters[0].order)
    // empty order falls back to index+1
    assertEquals(expected = 2, actual = chapters[1].order)
  }

  @Test
  fun unauthorizedRaisesReloginException() = runTest {
    server.enqueue(MockResponse.Builder().code(401).body("""{"detail":"no"}""").build())
    val result = runCatching { client.searchSource(baseUrl(), token = "expired", source = "A", keyword = "x") }
    assertTrue(result.isFailure, "expected failure, got: ${result.getOrNull()}")
    val exception = result.exceptionOrNull()
    assertTrue(exception is OnlineSourceException && exception.requiresRelogin, "actual: $exception")
  }
}
