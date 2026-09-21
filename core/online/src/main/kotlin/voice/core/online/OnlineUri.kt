package voice.core.online

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Canonical uri for an online chapter: `online://play?s=<source>&b=<bookId>&c=<chapterId>`.
 * The media pipeline hands these to [OnlineStreamingDataSource], which turns
 * them into real audio streams (direct link for script sources, downloaded
 * file for the main catalog).
 *
 * An online book itself is addressed by `online://book/<source>/<bookId>`,
 * which serves as its [voice.core.data.BookId] without ever touching Room.
 */
public object OnlineUri {

  public const val SCHEME: String = "online"
  public const val PLAY_HOST: String = "play"
  public const val BOOK_HOST: String = "book"

  /** Uri of the whole online book, usable as a [voice.core.data.BookId] value. */
  public fun buildBookUri(
    source: String,
    bookId: String,
  ): String {
    return "$SCHEME://$BOOK_HOST/${enc(source)}/${enc(bookId)}"
  }

  /** Returns null for uris that are not online book uris. */
  public fun parseBookUri(uri: String): OnlineBookRef? {
    val prefix = "$SCHEME://$BOOK_HOST/"
    if (!uri.startsWith(prefix)) return null
    val parts = uri.removePrefix(prefix).split('/')
    if (parts.size != 2) return null
    return OnlineBookRef(source = dec(parts[0]), bookId = dec(parts[1]))
  }

  public fun build(
    source: String,
    bookId: String,
    chapterId: String,
  ): String {
    return "$SCHEME://$PLAY_HOST?s=${enc(source)}&b=${enc(bookId)}&c=${enc(chapterId)}"
  }

  /** Returns null for uris that are not online play uris. */
  public fun parse(uri: String): OnlineChapterRef? {
    if (!uri.startsWith("$SCHEME://$PLAY_HOST?")) return null
    val params = uri.substringAfter('?')
      .split('&')
      .mapNotNull { part ->
        val idx = part.indexOf('=')
        if (idx <= 0) null else part.substring(0, idx) to dec(part.substring(idx + 1))
      }
      .toMap()
    val source = params["s"] ?: return null
    val bookId = params["b"] ?: return null
    val chapterId = params["c"] ?: return null
    return OnlineChapterRef(source, bookId, chapterId)
  }

  public fun isOnlineUri(uri: String): Boolean = uri.startsWith("$SCHEME://")

  private fun enc(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

  private fun dec(value: String): String = URLDecoder.decode(value, Charsets.UTF_8.name())
}

/** Reference to one online chapter, independent of any uri encoding. */
public data class OnlineChapterRef(
  val source: String,
  val bookId: String,
  val chapterId: String,
)

/** Reference to one online book, independent of any uri encoding. */
public data class OnlineBookRef(
  val source: String,
  val bookId: String,
) {
  /** Same key [OnlineSourceService] uses for shelf entries. */
  public val key: String
    get() = "$source::$bookId"
}
