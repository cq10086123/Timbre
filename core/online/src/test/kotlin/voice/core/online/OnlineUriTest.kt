package voice.core.online

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OnlineUriTest {

  @Test
  fun `book uri round trip`() {
    val uri = OnlineUri.buildBookUri(source = "main", bookId = "42")

    assertEquals(expected = "online://book/main/42", actual = uri)
    assertEquals(expected = OnlineBookRef("main", "42"), actual = OnlineUri.parseBookUri(uri))
    assertEquals(expected = "main::42", actual = OnlineUri.parseBookUri(uri)?.key)
  }

  @Test
  fun `book uri round trip with interface name and special characters`() {
    val uri = OnlineUri.buildBookUri(source = "A", bookId = "id/with spaces&more")

    assertEquals(expected = OnlineBookRef("A", "id/with spaces&more"), actual = OnlineUri.parseBookUri(uri))
  }

  @Test
  fun `parse book uri rejects other uris`() {
    assertNull(OnlineUri.parseBookUri("online://book/only-source"))
    assertNull(OnlineUri.parseBookUri("online://play?s=main&b=42&c=1"))
    assertNull(OnlineUri.parseBookUri("file:///audiobooks/book"))
    assertNull(OnlineUri.parseBookUri(""))
  }

  @Test
  fun `is online uri`() {
    assertTrue(OnlineUri.isOnlineUri("online://book/main/42"))
    assertTrue(OnlineUri.isOnlineUri("online://play?s=A&b=1&c=2"))
    assertFalse(OnlineUri.isOnlineUri("https://nas.example.com/dav/book"))
    assertFalse(OnlineUri.isOnlineUri("file:///audiobooks/book"))
  }
}
