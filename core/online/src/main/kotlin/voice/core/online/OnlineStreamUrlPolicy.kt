package voice.core.online

/**
 * Url policy for third party audio cdn links.
 *
 * Some of the script sources hand out links on hosts whose TLS certificate is
 * expired or issued for another domain. Android refuses the handshake
 * (CertificateExpiredException / "Chain validation failed"), so the stream can
 * never be opened over https. These hosts still serve the same audio over
 * plain http, which the app allows (see usesCleartextTraffic in the manifest),
 * so such links are transparently downgraded instead of failing playback.
 *
 * The same list is maintained by the browser extension of the download site
 * (`CERT_BROKEN_HOSTS` in its background.js).
 */
public object OnlineStreamUrlPolicy {

  /** Hosts whose https endpoint cannot be verified; matched with subdomains. */
  private val CERT_BROKEN_HOSTS = listOf("ting13.top")

  /**
   * Downgrades a known cert broken https url to http; every other url is
   * returned unchanged.
   */
  public fun applyCertFallback(url: String): String {
    if (!url.startsWith("https://", ignoreCase = true)) return url
    val remainder = url.substring("https://".length)
    val host = remainder
      .substringBefore('/')
      .substringBefore('?')
      .substringBefore('#')
      .substringAfter('@')
      .substringBefore(':')
      .lowercase()
    if (host.isEmpty()) return url
    val broken = CERT_BROKEN_HOSTS.any { known -> host == known || host.endsWith(".$known") }
    if (!broken) return url
    return "http://$remainder"
  }
}
