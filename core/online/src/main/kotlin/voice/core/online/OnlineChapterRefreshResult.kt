package voice.core.online

/**
 * The outcome of refreshing the chapter list of an online book against the
 * source. A refresh never shrinks what is stored on a failure: the shelf
 * keeps the previous chapters and the playback continues with them.
 */
public sealed interface OnlineChapterRefreshResult {

  /** The source reported more chapters than the shelf knew. */
  public data class Updated(
    val added: Int,
    val total: Int,
  ) : OnlineChapterRefreshResult

  /** The shelf already knows every chapter of the source. */
  public data class UpToDate(val total: Int) : OnlineChapterRefreshResult

  /** The chapter list could not be fetched; the shelf is unchanged. */
  public data class Failed(val message: String?) : OnlineChapterRefreshResult
}
