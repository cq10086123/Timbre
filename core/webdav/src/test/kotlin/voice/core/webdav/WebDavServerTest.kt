package voice.core.webdav

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
class WebDavServerTest {

  @Test
  fun matchesUrlsWithinTheServerPath() {
    val server = WebDavServer(
      id = "1",
      name = "nas",
      baseUrl = "https://nas.example.com:5006/dav",
      username = "user",
      encryptedPassword = "",
    )

    assertTrue(server.matches(Uri.parse("https://nas.example.com:5006/dav/book/01.mp3")))
    assertTrue(server.matches(Uri.parse("https://nas.example.com:5006/dav")))
    assertFalse(server.matches(Uri.parse("https://nas.example.com:5006/other/book/01.mp3")))
    assertFalse(server.matches(Uri.parse("http://nas.example.com:5006/dav/book/01.mp3")))
    assertFalse(server.matches(Uri.parse("content://primary/Audiobooks/book")))
  }

  @Test
  fun matchesServersWithoutPath() {
    val server = WebDavServer(
      id = "1",
      name = "nas",
      baseUrl = "http://192.168.1.10:8080",
      username = "user",
      encryptedPassword = "",
    )

    assertTrue(server.matches(Uri.parse("http://192.168.1.10:8080/dav/x/01.mp3")))
    assertFalse(server.matches(Uri.parse("http://192.168.1.11:8080/dav/x/01.mp3")))
  }

  @Test
  fun normalizesBaseUrls() {
    assertEquals(
      expected = "https://nas.example.com:5006/dav",
      actual = WebDavLibrary.normalizeBaseUrl(" https://nas.example.com:5006/dav/ "),
    )

    kotlin.test.assertFailsWith<IllegalArgumentException> {
      WebDavLibrary.normalizeBaseUrl("ftp://nas.example.com")
    }
    kotlin.test.assertFailsWith<IllegalArgumentException> {
      WebDavLibrary.normalizeBaseUrl("nas.example.com")
    }
  }
}
