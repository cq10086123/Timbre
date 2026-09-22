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

  @Test
  fun `a host learned at runtime is downgraded afterwards`() {
    val url = "https://new-broken-cdn.example.com/a.mp3"
    // unknown host: left alone until the handshake actually fails once
    assertEquals(url, OnlineStreamUrlPolicy.applyCertFallback(url))
    OnlineStreamUrlPolicy.rememberCertBroken(url)
    assertEquals(
      "http://new-broken-cdn.example.com/a.mp3",
      OnlineStreamUrlPolicy.applyCertFallback("https://new-broken-cdn.example.com/a.mp3"),
    )
    // subdomains of the learned host are covered too
    assertEquals(
      "http://x.new-broken-cdn.example.com/a.mp3",
      OnlineStreamUrlPolicy.applyCertFallback("https://x.new-broken-cdn.example.com/a.mp3"),
    )
    OnlineStreamUrlPolicy.forgetRuntimeHosts()
    assertEquals(url, OnlineStreamUrlPolicy.applyCertFallback(url))
  }

  @Test
  fun `downgradeToHttp only rewrites https urls`() {
    assertEquals("http://a.example.com/x", OnlineStreamUrlPolicy.downgradeToHttp("https://a.example.com/x"))
    assertEquals(null, OnlineStreamUrlPolicy.downgradeToHttp("http://a.example.com/x"))
    assertEquals(null, OnlineStreamUrlPolicy.downgradeToHttp("not a url"))
  }

  @Test
  fun `isCertBroken matches the seeded extension list`() {
    assertEquals(true, OnlineStreamUrlPolicy.isCertBroken("mp3nn.ting13.top"))
    assertEquals(true, OnlineStreamUrlPolicy.isCertBroken("TING13.TOP"))
    assertEquals(false, OnlineStreamUrlPolicy.isCertBroken("ting13.top.example.com"))
    assertEquals(false, OnlineStreamUrlPolicy.isCertBroken("file.hgeuz.cn"))
  }
}
