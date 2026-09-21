package voice.core.online

import kotlinx.serialization.Serializable

/**
 * A book added from the online source to the in-app shelf. Persisted in its
 * own DataStore - no Room schema involvement, and deleting it never touches
 * the files on the download server.
 */
@Serializable
public data class OnlineBook(
  /** Which source this book belongs to ("main" or an interface name). */
  val source: String,
  /** Source specific book id. */
  val bookId: String,
  val title: String,
  val author: String = "",
  val cover: String = "",
  val chapters: List<OnlineChapter> = emptyList(),
  val addedAt: Long = 0L,
) {
  /** Stable unique key: source and bookId namespaces are independent per source. */
  public val key: String
    get() = "$source::$bookId"
}
