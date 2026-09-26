package voice.core.online

import kotlinx.serialization.Serializable

/** The direct audio address a source resolved for one chapter. */
@Serializable
public data class OnlineAudio(
  val url: String,
  /** Request headers the stream needs (referer, user agent, cookies). */
  val headers: Map<String, String> = emptyMap(),
  /** Epoch millis after which the url is expected to stop working; 0 = unknown. */
  val expiresAt: Long = 0L,
)
