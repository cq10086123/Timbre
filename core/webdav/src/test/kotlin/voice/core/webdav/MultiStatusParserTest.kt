package voice.core.webdav

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith
import java.io.StringReader
import kotlin.test.Test

@RunWith(AndroidJUnit4::class)
class MultiStatusParserTest {

  @Test
  fun parsesListingWithNamespaces() = runTest {
    val body = """
      <?xml version="1.0" encoding="utf-8"?>
      <d:multistatus xmlns:d="DAV:">
        <d:response>
          <d:href>/dav/</d:href>
          <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:propstat>
        </d:response>
        <d:response>
          <d:href>/dav/%E4%B8%89%E4%BD%93/</d:href>
          <d:propstat><d:prop>
            <d:displayname>三体</d:displayname>
            <d:resourcetype><d:collection/></d:resourcetype>
            <d:getlastmodified>Wed, 12 Aug 2020 10:00:00 GMT</d:getlastmodified>
          </d:prop></d:propstat>
        </d:response>
        <d:response>
          <d:href>/dav/book/01.mp3</d:href>
          <d:propstat><d:prop>
            <d:getcontentlength>123456</d:getcontentlength>
            <d:getlastmodified>Wed, 12 Aug 2020 10:00:00 GMT</d:getlastmodified>
          </d:prop></d:propstat>
        </d:response>
      </d:multistatus>
    """.trimIndent()

    val resources = MultiStatusParser.parse(StringReader(body), baseUrl = "https://nas.example.com:5006/dav", rootUrl = "https://nas.example.com:5006")

    assertEquals(expected = 2, actual = resources.size)
    val folder = resources[0]
    assertEquals(expected = "https://nas.example.com:5006/dav/%E4%B8%89%E4%BD%93/", actual = folder.url)
    assertEquals(expected = "三体", actual = folder.name)
    assertTrue(folder.isDirectory)
    assertEquals(expected = 1597226400000L, actual = folder.lastModified)

    val file = resources[1]
    assertEquals(expected = "https://nas.example.com:5006/dav/book/01.mp3", actual = file.url)
    assertEquals(expected = "01.mp3", actual = file.name)
    assertTrue(!file.isDirectory)
    assertEquals(expected = 123456L, actual = file.contentLength)
  }

  @Test
  fun fallsBackToHrefWhenDisplayNameIsMissing() = runTest {
    val body = """
      <?xml version="1.0"?>
      <D:multistatus xmlns:D="DAV:">
        <D:response>
          <D:href>https://other-host.example.com/dav/Folder%20Name/</D:href>
          <D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype></D:propstat>
        </D:response>
      </D:multistatus>
    """.trimIndent()

    val resources = MultiStatusParser.parse(StringReader(body), baseUrl = "https://nas.example.com/dav", rootUrl = "https://nas.example.com")

    assertEquals(expected = 1, actual = resources.size)
    assertEquals(expected = "https://nas.example.com/dav/Folder%20Name/", actual = resources[0].url)
    assertEquals(expected = "Folder Name", actual = resources[0].name)
    assertTrue(resources[0].isDirectory)
  }

  @Test
  fun keepsPlusCharactersInHrefs() = runTest {
    val body = """
      <?xml version="1.0"?>
      <multistatus xmlns="DAV:">
        <response>
          <href>/dav/a+b/01.mp3</href>
        </response>
      </multistatus>
    """.trimIndent()

    val resources = MultiStatusParser.parse(StringReader(body), baseUrl = "https://nas.example.com/dav", rootUrl = "https://nas.example.com")

    assertEquals(expected = "a+b", actual = resources.single().name)
  }
}
