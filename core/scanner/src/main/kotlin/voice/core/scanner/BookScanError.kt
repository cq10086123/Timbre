package voice.core.scanner

import voice.core.data.BookId

/**
 * A book that could not be imported, or could only be imported partially.
 *
 * Import failures used to be swallowed: the book either never showed up on the
 * shelf or the placeholder of a running import vanished once the scan moved on.
 * The shelf reports this to the user instead, together with a retry.
 */
public data class BookScanError(
  val bookId: BookId,
  val kind: Kind,
  /** How many chapters could not be analyzed. 0 for [Kind.Unreachable]. */
  val failedChapters: Int = 0,
) {
  public enum class Kind {
    /**
     * The folder of the book could not be read, for example because the server
     * it lives on is unreachable or the storage was removed.
     */
    Unreachable,

    /** The audio files were found, but could not be analyzed. */
    AnalysisFailed,
  }
}
