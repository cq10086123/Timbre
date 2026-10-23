package voice.core.online

import kotlinx.serialization.Serializable

/**
 * A book added from the online source to the in-app shelf. Persisted in its
 * own DataStore - no Room schema involvement, and deleting it never touches
 * the files on the download server.
 */
@Serializable
public data class OnlineBook(
  /** Which source this book belongs to (an interface name, e.g. A). */
  val source: String,
  /** Source specific book id. */
  val bookId: String,
  val title: String,
  val author: String = "",
  val cover: String = "",
  /**
   * The remote url [cover] was downloaded from. Kept even after [cover] points
   * at the local file, because that file is the only copy of the image: when
   * it disappears (the book is removed and restored, the folder is cleaned)
   * the url is the only way to get the cover back.
   */
  val coverUrl: String? = null,
  val chapters: List<OnlineChapter> = emptyList(),
  val addedAt: Long = 0L,
  /**
   * The `OnlineChapter.id` that was played last, persisted so a killed process
   * or a reboot resumes the book where the user left off instead of at the
   * first chapter. Empty when the book was never played.
   */
  val currentChapterId: String = "",
  /** The position inside [currentChapterId] in milliseconds. */
  val positionMs: Long = 0L,
  /** Milliseconds to skip at the start of every chapter of this book. 0 = off. */
  val skipIntroMs: Long = 0L,
  /** Milliseconds to cut off the end of every chapter of this book. 0 = off. */
  val skipOutroMs: Long = 0L,
  /** Whether silence skipping is enabled while playing this book. */
  val skipSilence: Boolean = false,
  /** The playback speed persisted for this book. 1f = normal. */
  val playbackSpeed: Float = 1f,
  /** The volume gain persisted for this book in decibels. 0f = none. */
  val gain: Float = 0f,
) {
  /** Stable unique key: source and bookId namespaces are independent per source. */
  public val key: String
    get() = "$source::$bookId"
}
