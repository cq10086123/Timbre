package voice.core.online

import java.util.concurrent.ConcurrentHashMap

/**
 * Url policy for third party audio cdn links.
 *
 * Some script sources hand out links on hosts whose TLS certificate is expired
 * or issued for another domain. Android refuses the handshake
 * (CertificateExpiredException / "Chain validation failed"), so the stream can
 * never be opened over https, while the same host usually still serves the
 * audio over plain http (the app allows cleartext, see usesCleartextTraffic).
 *
 * Rather than hardcoding every broken host, the data source reports the host
 * here after a failed https handshake ([rememberCertBroken]); the stream is
 * then retried over http and every later request for the same host skips the
 * doomed https attempt for the rest of the session. New broken hosts therefore
 * heal themselves without a code change.
 */
public object OnlineStreamUrlPolicy {

  /**
   * Hosts known to have a broken certificate. Pre-seeded with the ones the
   * download site's browser extension already knows (`CERT_BROKEN_HOSTS` in
   * background.js) purely to skip one failing round trip; hosts discovered at
   * runtime are added by [rememberCertBroken].
   */
  private val certBrokenHosts: MutableSet<String> = ConcurrentHashMap.newKeySet<String>().apply {
    add("ting13.top")
  }

  /**
   * Downgrades https links on hosts with a broken certificate to http; every
   * other url is returned unchanged.
   */
  public fun applyCertFallback(url: String): String {
    val host = hostOf(url) ?: return url
    if (!isCertBroken(host)) return url
    return downgradeToHttp(url) ?: url
  }

  /** Remembers the host of [url] as cert broken for the rest of the session. */
  public fun rememberCertBroken(url: String) {
    val host = hostOf(url) ?: return
    certBrokenHosts.add(host)
  }

  /**
   * Rewrites an https url of the same address to http, or null when the url is
   * not https / cannot be parsed.
   */
  public fun downgradeToHttp(url: String): String? {
    if (!url.startsWith("https://", ignoreCase = true)) return null
    return "http://" + url.substring("https://".length)
  }

  /** True when [host] (or a parent domain of it) is known to be cert broken. */
  public fun isCertBroken(host: String): Boolean {
    val normalized = host.lowercase()
    return certBrokenHosts.any { known -> normalized == known || normalized.endsWith(".$known") }
  }

  /** Test hook: forgets hosts learned at runtime (keeps the seeded ones). */
  internal fun forgetRuntimeHosts() {
    certBrokenHosts.retainAll(setOf("ting13.top"))
  }

  private fun hostOf(url: String): String? {
    val schemeEnd = url.indexOf("://")
    if (schemeEnd < 0) return null
    return url.substring(schemeEnd + 3)
      .substringBefore('/')
      .substringBefore('?')
      .substringBefore('#')
      .substringAfter('@')
      .substringBefore(':')
      .lowercase()
      .takeIf { it.isNotEmpty() }
  }
}
