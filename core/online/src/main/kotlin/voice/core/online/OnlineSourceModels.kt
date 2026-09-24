package voice.core.online

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One source (interface) exposed by the download site: /api/interfaces. */
@Serializable
public data class OnlineSourceInfo(
  val name: String = "",
  val displayName: String = "",
  val type: String = "",
  val enabled: Boolean = true,
)

/** Unified search result across all sources. */
@Serializable
public data class OnlineSearchResult(
  /** Which source this result came from: "main" or the interface name (A, B, ...). */
  val source: String = "",
  /** Source specific book id. Only meaningful together with [source]. */
  val bookId: String = "",
  val title: String = "",
  val author: String = "",
  val cover: String = "",
  val intro: String = "",
  val trackCount: Int = 0,
)

/** Unified chapter of an online book. */
@Serializable
public data class OnlineChapter(
  /** Source specific chapter id. */
  val id: String = "",
  val title: String = "",
  val durationSeconds: Int = 0,
  val order: Int = 0,
)

/** Batch download task status: /api/download/batch/{task_id}. */
@Serializable
public data class OnlineBatchStatus(
  val taskId: String = "",
  val status: String = "",
  val total: Int = 0,
  val completed: Int = 0,
  val error: String? = null,
) {
  public val isDone: Boolean
    get() = status == "done" || status == "completed"

  public val isFailed: Boolean
    get() = status == "failed" || status == "cancelled"
}

/** One album already downloaded on the site (path is the streaming key). */
@Serializable
public data class FilesAlbum(
  val name: String = "",
  val count: Int = 0,
  val files: List<FilesEntry> = emptyList(),
  /** Main-catalog album id recorded by the site, when known. */
  @SerialName("album_id") val albumId: String = "",
)

/** One downloaded file entry. */
@Serializable
public data class FilesEntry(
  val name: String = "",
  val path: String = "",
  val size: Long = 0,
  /** Main-catalog track id recorded by the site, when known. */
  @SerialName("track_id") val trackId: String = "",
)
