package voice.core.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SourceResultParserTest {

  private val parser = SourceResultParser()

  @Test
  fun `parses object page`() {
    val page = parser.parsePage(
      """{"items":[{"id":"1","title":"Book"}],"nextPage":"2","hasMore":true}""",
    )
    assertEquals(1, page.items.size)
    assertEquals("Book", page.items[0].title)
    assertEquals("2", page.nextPage)
    assertTrue(page.hasMore)
  }

  @Test
  fun `parses bare array page with unknown keys`() {
    val page = parser.parsePage("""[{"id":"1","title":"Book","whatever":42}]""")
    assertEquals(1, page.items.size)
    assertEquals("", page.nextPage)
  }

  @Test
  fun `parses chapters and fills order`() {
    val chapters = parser.parseChapters(
      """[{"id":"c2","title":"Two"},{"id":"c1","title":"One","order":5,"durationSeconds":30,"extra":"x"}]""",
    )
    assertEquals(2, chapters.size)
    assertEquals(1, chapters[0].order)
    assertEquals(5, chapters[1].order)
    assertEquals(30, chapters[1].durationSeconds)
    assertEquals("x", chapters[1].extra)
  }

  @Test
  fun `parses audio url string`() {
    val audio = parser.parseAudio("\"https://x/y.mp3\"")
    assertEquals("https://x/y.mp3", audio.url)
  }

  @Test
  fun `parses audio object with headers`() {
    val audio = parser.parseAudio(
      """{"url":"https://x/y.mp3","headers":{"Referer":"https://x/"},"expiresAt":123}""",
    )
    assertEquals("https://x/y.mp3", audio.url)
    assertEquals("https://x/", audio.headers["Referer"])
    assertEquals(123L, audio.expiresAt)
  }

  @Test
  fun `rejects audio without url`() {
    try {
      val _ = parser.parseAudio("""{"headers":{}}""")
      fail("expected SourceResultException")
    } catch (e: SourceResultException) {
      assertTrue(e.message!!.contains("url"))
    }
  }

  @Test
  fun `rejects garbage json`() {
    try {
      val _ = parser.parsePage("not json")
      fail("expected SourceResultException")
    } catch (e: SourceResultException) {
      assertTrue(e.message!!.contains("invalid json"))
    }
  }
}
