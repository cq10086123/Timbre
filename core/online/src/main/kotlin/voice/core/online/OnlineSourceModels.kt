package voice.core.online

import kotlinx.serialization.Serializable

/** One source (interface) exposed by the download site: /api/interfaces. */
@Serializable
public data class OnlineSourceInfo(
  val name: String = "",
  val displayName: String = "",
  val enabled: Boolean = true,
)

/** Unified search result across all sources. */
@Serializable
public data class OnlineSearchResult(
  /** Which source this result came from: the interface name (A, B, ...). */
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
