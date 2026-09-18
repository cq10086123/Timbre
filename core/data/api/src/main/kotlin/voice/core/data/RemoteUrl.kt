package voice.core.data

/**
 * Canonical form of a remote (http/https) url that identifies a book or a
 * chapter.
 *
 * WebDAV servers hand out the very same folder both with and without a trailing
 * slash (`https://nas/dav/Book` and `https://nas/dav/Book/`), depending on the
 * request that listed it and on the server implementation. Book ids are plain
 * strings, so a scan that derives the id from a different spelling than the
 * previous one would treat the book as a brand new one: the stored book -
 * including the listening progress and the bookmarks - would be deactivated and
 * the shelf would either lose the book or show a duplicate.
 *
 * Dropping the trailing slash keeps the id stable across scans. Local uris and
 * urls that carry a query or a fragment are returned unchanged.
 */
public fun normalizeRemoteUrl(value: String): String {
  val schemeSeparator = value.indexOf("://")
  if (schemeSeparator <= 0) return value
  val scheme = value.substring(0, schemeSeparator)
  if (!scheme.equals("http", ignoreCase = true) && !scheme.equals("https", ignoreCase = true)) {
    return value
  }
  // a url with a query or a fragment is not a folder url, so it is left alone
  if (value.any { it == '?' || it == '#' }) return value
  val trimmed = value.trimEnd('/')
  // the authority must survive: "http://" and "https:///" stay as they are
  return if (trimmed.length <= schemeSeparator + 3) value else trimmed
}
