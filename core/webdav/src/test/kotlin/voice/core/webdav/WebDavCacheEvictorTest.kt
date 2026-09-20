package voice.core.webdav

import androidx.media3.datasource.cache.CacheSpan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebDavCacheEvictorTest {

  private fun span(
    key: String,
    lastTouch: Long,
  ): CacheSpan {
    return CacheSpan(key, 0L, 1024L, lastTouch, null)
  }

  @Test
  fun `speculative data of inactive books is evicted first`() {
    val activeBook = "https://nas/dav/b/"
    val classifier = WebDavSpanClassifier()
    classifier.markActiveBook(activeBook)
    classifier.markSpeculative("https://nas/dav/b/ch02.mp3", activeBook)
    classifier.markSpeculative("https://nas/dav/a/ch01.mp3", "https://nas/dav/a")

    val spans = listOf(
      span("https://nas/dav/b/ch02.mp3", lastTouch = 300L),
      span("https://nas/dav/a/ch01.mp3", lastTouch = 100L),
      span("https://nas/dav/b/ch01.mp3", lastTouch = 200L), // consumed by playback
    )

    val order = evictionOrder(spans, classifier::isSpeculativeOfInactiveBook)

    assertEquals(
      expected = listOf(
        "https://nas/dav/a/ch01.mp3", // speculative of an inactive book
        "https://nas/dav/b/ch01.mp3", // consumed, oldest
        "https://nas/dav/b/ch02.mp3", // speculative of the active book, newest
      ),
      actual = order.map { it.key },
    )
  }

  @Test
  fun `touched data wins against older data of the same tier`() {
    val order = evictionOrder(
      spans = listOf(
        span("a", lastTouch = 50L),
        span("b", lastTouch = 90L),
        span("c", lastTouch = 70L),
      ),
      isSpeculativeOfInactiveBook = { false },
    )

    assertEquals(expected = listOf("a", "c", "b"), actual = order.map { it.key })
  }

  @Test
  fun `forgetUrl drops the speculative label`() {
    val classifier = WebDavSpanClassifier()
    classifier.markActiveBook("https://nas/dav/b")
    classifier.markSpeculative("https://nas/dav/a/ch01.mp3", "https://nas/dav/a")

    assertTrue(classifier.isSpeculativeOfInactiveBook("https://nas/dav/a/ch01.mp3"))

    classifier.forgetUrl("https://nas/dav/a/ch01.mp3")

    assertFalse(classifier.isSpeculativeOfInactiveBook("https://nas/dav/a/ch01.mp3"))
  }
}
