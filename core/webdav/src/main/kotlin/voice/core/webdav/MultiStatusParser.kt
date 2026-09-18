package voice.core.webdav

import android.net.Uri
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.Reader
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Parses the `207 Multi-Status` body of a WebDAV PROPFIND request.
 *
 * [baseUrl] is the url the request was made against, [rootUrl] is the origin
 * `scheme://host[:port]`. Hrefs in the response are either absolute paths from
 * the server root, relative to the request url, or full urls.
 */
internal object MultiStatusParser {

  fun parse(
    reader: Reader,
    baseUrl: String,
    rootUrl: String,
  ): List<WebDavResource> {
    val parser = XmlPullParserFactory.newInstance().newPullParser()
    parser.setInput(reader)

    val resources = mutableListOf<WebDavResource>()
    var inResponse = false
    var href: String? = null
    var displayName: String? = null
    var contentLength: Long = -1L
    var lastModified: Long = 0L
    var isCollection = false

    var event = parser.eventType
    while (event != XmlPullParser.END_DOCUMENT) {
      when (event) {
        XmlPullParser.START_TAG -> when (parser.localName()) {
          "response" -> {
            inResponse = true
            href = null
            displayName = null
            contentLength = -1L
            lastModified = 0L
            isCollection = false
          }
          "href" -> if (inResponse) href = parser.nextText()
          "displayname" -> if (inResponse) displayName = parser.nextText()
          "getcontentlength" -> if (inResponse) contentLength = parser.nextText().toLongOrNull() ?: -1L
          "getlastmodified" -> if (inResponse) lastModified = parseDate(parser.nextText())
          "collection" -> if (inResponse) isCollection = true
        }
        XmlPullParser.END_TAG -> if (parser.localName() == "response" && inResponse) {
          inResponse = false
          href?.let { nonNullHref ->
            buildResource(nonNullHref, baseUrl, rootUrl, displayName, isCollection, contentLength, lastModified)
              ?.let(resources::add)
          }
        }
      }
      event = parser.next()
    }
    return resources
  }

  private fun buildResource(
    href: String,
    baseUrl: String,
    rootUrl: String,
    displayName: String?,
    isCollection: Boolean,
    contentLength: Long,
    lastModified: Long,
  ): WebDavResource? {
    val url = hrefToUrl(href, baseUrl, rootUrl) ?: return null
    // Skip the response entry of the requested directory itself.
    if (url == baseUrl.trimEnd('/') + "/") return null
    val name = displayName?.takeIf { it.isNotBlank() }
      ?: decode(href).trimEnd('/').substringAfterLast('/').ifEmpty { return null }
    return WebDavResource(
      url = url,
      name = name,
      isDirectory = isCollection || href.endsWith("/"),
      contentLength = contentLength,
      lastModified = lastModified,
    )
  }

  private fun hrefToUrl(
    href: String,
    baseUrl: String,
    rootUrl: String,
  ): String? {
    val trimmed = href.trim()
    if (trimmed.isEmpty()) return null
    return when {
      trimmed.startsWith("http://") || trimmed.startsWith("https://") -> {
        val path = Uri.parse(trimmed).encodedPath ?: return null
        rootUrl + path
      }
      trimmed.startsWith("/") -> rootUrl + trimmed
      else -> baseUrl.trimEnd('/') + "/" + trimmed
    }
  }

  private fun XmlPullParser.localName(): String {
    return name.substringAfter(':')
  }

  private fun decode(value: String): String {
    return runCatching { URLDecoder.decode(value.replace("+", "%2B"), "UTF-8") }
      .getOrDefault(value)
  }

  private fun parseDate(value: String): Long {
    val formats = listOf(
      SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US),
      SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US),
      SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US),
    )
    formats.forEach { format ->
      format.timeZone = TimeZone.getTimeZone("UTC")
      val parsed = runCatching { format.parse(value.trim()) }.getOrNull()
      if (parsed != null) {
        return parsed.time
      }
    }
    return 0L
  }
}
