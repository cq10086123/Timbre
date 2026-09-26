package voice.core.source

import kotlinx.serialization.Serializable

/** A book a plugin source handed back from a search or a details call. */
@Serializable
public data class SourceBook(
  /** Source specific book id, only meaningful together with the source. */
  val id: String,
  val title: String,
  val author: String = "",
  val cover: String = "",
  val intro: String = "",
  /**
   * Opaque payload the source wants back on later calls. Never interpreted
   * by the player, only round-tripped.
   */
  val extra: String = "",
)

/** One chapter of a plugin source book. */
@Serializable
public data class SourceChapter(
  /** Source specific chapter id. */
  val id: String,
  val title: String,
  val order: Int = 0,
  /** Duration in seconds, when the source reports one. 0 = unknown. */
  val durationSeconds: Int = 0,
  /** Opaque payload the source wants back in the audio call. */
  val extra: String = "",
)

/** The direct audio address a plugin source resolved for one chapter. */
@Serializable
public data class SourceAudio(
  val url: String,
  /** Request headers the stream needs (referer, user agent, cookies). */
  val headers: Map<String, String> = emptyMap(),
  /**
   * Epoch millis after which the url is expected to stop working, when the
   * source knows. 0 = the source does not promise an expiry.
   */
  val expiresAt: Long = 0L,
)

/** One page of search results. */
@Serializable
public data class SourcePage(
  val items: List<SourceBook> = emptyList(),
  /** Opaque page token the source handed out, for future pagination. */
  val nextPage: String = "",
  val hasMore: Boolean = false,
)
