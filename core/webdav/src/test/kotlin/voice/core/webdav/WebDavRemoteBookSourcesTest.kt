package voice.core.webdav

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import voice.core.data.BookId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class WebDavRemoteBookSourcesTest {

  private lateinit var server: MockWebServer

  private val serverId = "server"

  @Before
  fun setUp() {
    server = MockWebServer()
    server.start()
  }

  @After
  fun tearDown() {
    server.close()
  }

  @Test
  fun `library root expands into its child books`() = runTest {
    server.enqueue(multiStatus("/dav/", "/dav/book1/", "/dav/01.mp3"))
    val sources = MemoryDataStore(listOf(libraryRootSource()))
    val rootUrl = libraryRootUrl()

    val books = remoteBookSources(sources).books().first()

    assertEquals(
      expected = listOf("$rootUrl/book1/", "$rootUrl/01.mp3"),
      actual = books.map { it.uri.toString() },
    )
    // remembered, so a later failing listing can fall back to exactly these books
    assertEquals(
      expected = listOf("$rootUrl/book1/", "$rootUrl/01.mp3"),
      actual = sources.data.first().first().lastExpandedUrls,
    )
  }

  @Test
  fun `failing listing falls back to the books of the last successful expansion`() = runTest {
    server.enqueue(multiStatus("/dav/", "/dav/book1/", "/dav/book2/"))
    val sources = MemoryDataStore(listOf(libraryRootSource()))
    val rootUrl = libraryRootUrl()

    val importedBooks = remoteBookSources(sources).books().first()
    val importedIds = importedBooks.map { BookId(it.uri) }
    assertEquals(expected = 2, actual = importedIds.size)

    // the nas is offline. A fresh client is used because the directory listing
    // of a client is cached for a short time (which is what makes a scan cheap
    // in the first place)
    server.enqueue(MockResponse.Builder().code(503).build())
    val rescannedBooks = remoteBookSources(sources).books().first()

    // the books keep the very same ids, so the scan does not deactivate them
    // and the shelf keeps showing them with their stored metadata
    assertEquals(expected = importedIds, actual = rescannedBooks.map { BookId(it.uri) })
    assertEquals(
      expected = listOf("$rootUrl/book1/", "$rootUrl/book2/"),
      actual = rescannedBooks.map { it.uri.toString() },
    )
    // the failed attempt does not overwrite what is known
    assertEquals(
      expected = listOf("$rootUrl/book1/", "$rootUrl/book2/"),
      actual = sources.data.first().first().lastExpandedUrls,
    )
  }

  @Test
  fun `failing listing without a remembered expansion falls back to the registered url`() = runTest {
    server.enqueue(MockResponse.Builder().code(503).build())
    val sources = MemoryDataStore(listOf(libraryRootSource()))

    val books = remoteBookSources(sources).books().first()

    // nothing was imported from that root yet, so there is nothing to keep.
    // The registration itself shows up (with an import error) instead of
    // vanishing without a trace.
    assertEquals(expected = listOf(libraryRootUrl()), actual = books.map { it.uri.toString() })
  }

  @Test
  fun `a successful listing replaces the remembered books`() = runTest {
    server.enqueue(multiStatus("/dav/", "/dav/book1/"))
    val sources = MemoryDataStore(listOf(libraryRootSource()))
    remoteBookSources(sources).books().first()

    server.enqueue(multiStatus("/dav/", "/dav/book2/"))
    val books = remoteBookSources(sources).books().first()

    assertEquals(expected = listOf("${libraryRootUrl()}/book2/"), actual = books.map { it.uri.toString() })
    assertEquals(
      expected = listOf("${libraryRootUrl()}/book2/"),
      actual = sources.data.first().first().lastExpandedUrls,
    )
  }

  @Test
  fun `a deleted server removes its books`() = runTest {
    server.enqueue(multiStatus("/dav/", "/dav/book1/"))
    val sources = MemoryDataStore(listOf(libraryRootSource()))
    remoteBookSources(sources).books().first()

    val withoutServer = WebDavRemoteBookSources(
      sourcesStore = sources,
      client = WebDavClient(),
      resolver = WebDavCredentialResolver(
        serversStore = MemoryDataStore(emptyList()),
        secrets = testWebDavSecrets(),
      ),
    )

    assertEquals(expected = emptyList(), actual = withoutServer.books().first())
  }

  @Test
  fun `removing a registration ignores the trailing slash of the book id`() = runTest {
    val sources = MemoryDataStore(listOf(libraryRootSource()))
    val remoteBookSources = remoteBookSources(sources)

    val removed = remoteBookSources.removeBookRegistration(BookId(Uri.parse("${libraryRootUrl()}/")))

    assertTrue(removed)
    assertEquals(expected = emptyList(), actual = sources.data.first())
  }

  @Test
  fun `removing a child book from a library root registration excludes it from future expansions`() = runTest {
    server.enqueue(multiStatus("/dav/", "/dav/book1/", "/dav/book2/"))
    val sources = MemoryDataStore(listOf(libraryRootSource()))
    val rootUrl = libraryRootUrl()
    val remoteBookSources = remoteBookSources(sources)

    val initialBooks = remoteBookSources.books().first()
    assertEquals(expected = 2, actual = initialBooks.size)

    val removed = remoteBookSources.removeBookRegistration(BookId(Uri.parse("$rootUrl/book1")))
    assertTrue(removed)

    server.enqueue(multiStatus("/dav/", "/dav/book1/", "/dav/book2/"))
    val updatedBooks = remoteBookSources.books().first()
    assertEquals(
      expected = listOf("$rootUrl/book2/"),
      actual = updatedBooks.map { it.uri.toString() },
    )
  }

  @Test
  fun `failing listing excludes previously removed child books from fallback`() = runTest {
    server.enqueue(multiStatus("/dav/", "/dav/book1/", "/dav/book2/"))
    val sources = MemoryDataStore(listOf(libraryRootSource()))
    val rootUrl = libraryRootUrl()
    val remoteBookSources = remoteBookSources(sources)

    remoteBookSources.books().first()

    val removed =
      remoteBookSources.removeBookRegistration(BookId(Uri.parse("$rootUrl/book1")))
    assertEquals(expected = true, actual = removed)

    server.enqueue(MockResponse.Builder().code(503).build())
    val rescannedBooks = remoteBookSources.books().first()

    assertEquals(
      expected = listOf("$rootUrl/book2/"),
      actual = rescannedBooks.map { it.uri.toString() },
    )
  }

  @Test
  fun `removing an unknown book keeps the registration`() = runTest {
    val sources = MemoryDataStore(listOf(libraryRootSource()))
    val remoteBookSources = remoteBookSources(sources)

    val removed = remoteBookSources.removeBookRegistration(BookId(Uri.parse("https://other.example.com/dav/Book")))

    assertEquals(expected = false, actual = removed)
    assertEquals(expected = 1, actual = sources.data.first().size)
  }

  private fun libraryRootUrl(): String = server.url("/dav").toString().trimEnd('/')

  private fun libraryRootSource(): WebDavBookSource {
    return WebDavBookSource(
      serverId = serverId,
      url = libraryRootUrl(),
      displayName = "NAS",
      mode = WebDavBookSource.Mode.LibraryRoot,
    )
  }

  private fun remoteBookSources(sources: MemoryDataStore<List<WebDavBookSource>>): WebDavRemoteBookSources {
    return WebDavRemoteBookSources(
      sourcesStore = sources,
      client = WebDavClient(),
      resolver = WebDavCredentialResolver(
        serversStore = MemoryDataStore(
          listOf(
            WebDavServer(
              id = serverId,
              name = "nas",
              baseUrl = libraryRootUrl(),
              username = "user",
              encryptedPassword = "secret",
            ),
          ),
        ),
        secrets = testWebDavSecrets(),
      ),
    )
  }

  private fun multiStatus(vararg hrefs: String): MockResponse {
    return MockResponse.Builder()
      .code(207)
      .body(multiStatusBody(*hrefs))
      .build()
  }

  private fun multiStatusBody(vararg hrefs: String): String {
    // the parts are joined explicitly: an interpolated block would make the
    // common indent of the raw string collapse to zero, leaving whitespace in
    // front of the xml declaration, which the parser rejects
    val responses = hrefs.joinToString(separator = "\n", transform = ::responseEntry)
    return buildString {
      append("<?xml version=\"1.0\"?>\n")
      append("<d:multistatus xmlns:d=\"DAV:\">\n")
      append(responses)
      append("\n</d:multistatus>")
    }
  }

  private fun responseEntry(href: String): String {
    val propstat = if (href.endsWith("/")) {
      "  <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat>"
    } else {
      listOf(
        "  <d:propstat><d:prop>",
        "    <d:getcontentlength>42</d:getcontentlength>",
        "    <d:getlastmodified>Wed, 01 Jan 2025 10:00:00 GMT</d:getlastmodified>",
        "  </d:prop></d:propstat>",
      ).joinToString(separator = "\n")
    }
    return listOf(
      "<d:response>",
      "  <d:href>$href</d:href>",
      propstat,
      "</d:response>",
    ).joinToString(separator = "\n")
  }
}
