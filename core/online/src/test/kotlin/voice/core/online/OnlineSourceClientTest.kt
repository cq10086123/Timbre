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
    assertTrue(body.contains("\"code\":\"XM-TEST-CARD\""))
    assertTrue(body.contains("\"captchaId\":\"cid-1\""))
    assertTrue(body.contains("\"captcha\":\"TN42\""))
  }

  @Test
  fun mainSearchMapsWithMainSource() = runTest {
    server.enqueue(
      ok(
        """{"success":true,"results":[{"albumId":61310701,"title":"T1","author":"A1","tracks":2115,
           "cover":"c.png","intro":"i"}]}""",
      ),
    )

    val results = client.searchMain(baseUrl(), token = "t", keyword = "凡人")

    assertEquals(expected = 1, actual = results.size)
    assertEquals(expected = OnlineSourceClient.SOURCE_MAIN, actual = results[0].source)
    assertEquals(expected = "61310701", actual = results[0].bookId)
    assertEquals(expected = 2115, actual = results[0].trackCount)
    val recorded = server.takeRequest()
    assertEquals(expected = "/api/search", actual = recorded.url.encodedPath)
    assertEquals(expected = "凡人", actual = recorded.url.queryParameter("keyword"))
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
  fun mainAlbumListNormalizesIdsAndOrders() = runTest {
    server.enqueue(
      ok(
        """{"success":true,"album_title":"B","track_total":2,
           "tracks":[{"trackId":516265274,"title":"c1","duration":677},
                     {"trackId":516265275,"title":"c2","duration":700}]}""",
      ),
    )

    val chapters = client.mainAlbumList(baseUrl(), token = "t", bookId = "61310701")

    assertEquals(expected = 2, actual = chapters.size)
    assertEquals(expected = "516265274", actual = chapters[0].id)
    assertEquals(expected = 1, actual = chapters[0].order)
    assertEquals(expected = 700, actual = chapters[1].durationSeconds)
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
  fun fileUrlPercentEncodesEachPathSegment() {
    val url = client.fileUrl(baseUrl(), "凡人修仙传/第1集.mp3")
    assertEquals(
      expected = baseUrl() + "/api/files/file/%E5%87%A1%E4%BA%BA%E4%BF%AE%E4%BB%99%E4%BC%A0/%E7%AC%AC1%E9%9B%86.mp3",
      actual = url,
    )
  }

  @Test
  fun unauthorizedRaisesReloginException() = runTest {
    server.enqueue(MockResponse.Builder().code(401).body("""{"detail":"no"}""").build())
    val result = runCatching { client.searchMain(baseUrl(), token = "expired", keyword = "x") }
    assertTrue(result.isFailure, "expected failure, got: ${result.getOrNull()}")
    val exception = result.exceptionOrNull()
    assertTrue(exception is OnlineSourceException && exception.requiresRelogin, "actual: $exception")
  }
}
