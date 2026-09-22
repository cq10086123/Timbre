package voice.core.online

import kotlin.test.Test
import kotlin.test.assertEquals

class OnlineStreamUrlPolicyTest {

  @Test
  fun `downgrades https links on cert broken hosts`() {
    assertEquals(
      "http://mp3nn.ting13.top/stream/abc.mp3?ts=1&sign=2",
      OnlineStreamUrlPolicy.applyCertFallback("https://mp3nn.ting13.top/stream/abc.mp3?ts=1&sign=2"),
    )
  }

  @Test
  fun `downgrades subdomains of cert broken hosts`() {
    assertEquals(
      "http://a.b.ting13.top/x.mp3",
      OnlineStreamUrlPolicy.applyCertFallback("https://a.b.ting13.top/x.mp3"),
    )
  }

  @Test
  fun `keeps healthy https hosts untouched`() {
    val url = "https://file.hgeuz.cn/stream/abc.mp3"
    assertEquals(url, OnlineStreamUrlPolicy.applyCertFallback(url))
    val kw = "https://car-lv.kuwo.cn/resource/1/trackmedia/long"
    assertEquals(kw, OnlineStreamUrlPolicy.applyCertFallback(kw))
  }

  @Test
  fun `keeps http and non matching hosts untouched`() {
    assertEquals("http://mp3nn.ting13.top/a.mp3", OnlineStreamUrlPolicy.applyCertFallback("http://mp3nn.ting13.top/a.mp3"))
    // a host that merely contains the known name must not match
    assertEquals(
      "https://ting13.top.example.com/a.mp3",
      OnlineStreamUrlPolicy.applyCertFallback("https://ting13.top.example.com/a.mp3"),
    )
  }
}
