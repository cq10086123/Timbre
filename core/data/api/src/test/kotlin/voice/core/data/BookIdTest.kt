package voice.core.data

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.serialization.json.Json
import org.junit.runner.RunWith
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

// Robolectric: the id constructors parse uris
@RunWith(AndroidJUnit4::class)
class BookIdTest {

  @Test
  fun trailingSlashOfRemoteUrlsIsDropped() {
    assertEquals(
      expected = "https://nas.example.com:5006/dav/Book",
      actual = normalizeRemoteUrl("https://nas.example.com:5006/dav/Book/"),
    )
    assertEquals(
      expected = "https://nas.example.com/dav/Book",
      actual = normalizeRemoteUrl("https://nas.example.com/dav/Book///"),
    )
    // the origin of the server is a valid book url as well
    assertEquals(
      expected = "https://nas.example.com",
      actual = normalizeRemoteUrl("https://nas.example.com/"),
    )
    assertEquals(
      expected = "http://192.168.1.10:8080",
      actual = normalizeRemoteUrl("http://192.168.1.10:8080"),
    )
  }

  @Test
  fun urlsWithoutHttpSchemeAreUnchanged() {
    assertEquals(
      expected = "content://com.android.externalstorage.documents/tree/primary%3AAudiobooks",
      actual = normalizeRemoteUrl("content://com.android.externalstorage.documents/tree/primary%3AAudiobooks"),
    )
    assertEquals(
      expected = "file:///storage/emulated/0/Audiobooks/Book/",
      actual = normalizeRemoteUrl("file:///storage/emulated/0/Audiobooks/Book/"),
    )
    // a url that only consists of the scheme must not lose its authority
    assertEquals(expected = "https://", actual = normalizeRemoteUrl("https://"))
    assertEquals(expected = "http://", actual = normalizeRemoteUrl("http://"))
    assertEquals(expected = "https:///", actual = normalizeRemoteUrl("https:///"))
  }

  @Test
  fun urlsWithQueryOrFragmentAreUnchanged() {
    assertEquals(
      expected = "https://nas.example.com/dav/Book/?token=abc",
      actual = normalizeRemoteUrl("https://nas.example.com/dav/Book/?token=abc"),
    )
    assertEquals(
      expected = "https://nas.example.com/dav/Book/#top",
      actual = normalizeRemoteUrl("https://nas.example.com/dav/Book/#top"),
    )
  }

  @Test
  fun normalizationIsIdempotent() {
    val normalized = normalizeRemoteUrl("https://nas.example.com/dav/Book/")
    assertEquals(expected = normalized, actual = normalizeRemoteUrl(normalized))
  }

  @Test
  fun bookIdIsStableRegardlessOfTheTrailingSlash() {
    val fromListing = BookId(Uri.parse("https://nas.example.com/dav/Book/"))
    val fromRegistration = BookId(Uri.parse("https://nas.example.com/dav/Book"))
    assertEquals(expected = fromRegistration, actual = fromListing)
    assertEquals(expected = "https://nas.example.com/dav/Book", actual = fromListing.value)
    // the first parameter of assertNotEquals is called "illegal", so it is passed positionally
    assertNotEquals(fromListing, BookId(Uri.parse("https://nas.example.com/dav/Other")))
  }

  @Test
  fun chapterIdIsStableRegardlessOfTheTrailingSlash() {
    assertEquals(
      expected = ChapterId(Uri.parse("https://nas.example.com/dav/Book/01.mp3")),
      actual = ChapterId(Uri.parse("https://nas.example.com/dav/Book/01.mp3/")),
    )
  }

  @Test
  fun serializationNormalizesIds() {
    assertEquals(
      expected = "\"https://nas.example.com/dav/Book\"",
      actual = Json.encodeToString(BookId.serializer(), BookId("https://nas.example.com/dav/Book/")),
    )
    assertEquals(
      expected = BookId("https://nas.example.com/dav/Book"),
      actual = Json.decodeFromString(BookId.serializer(), "\"https://nas.example.com/dav/Book/\""),
    )
    assertEquals(
      expected = ChapterId("https://nas.example.com/dav/Book/01.mp3"),
      actual = Json.decodeFromString(ChapterId.serializer(), "\"https://nas.example.com/dav/Book/01.mp3/\""),
    )
  }
}
