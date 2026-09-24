package voice.core.online

import kotlinx.serialization.Serializable

@Serializable
internal data class CaptchaResponse(
  val captchaId: String = "",
  val svg: String = "",
)

@Serializable
internal data class LoginResponse(
  val success: Boolean = false,
  val token: String = "",
)

@Serializable
internal data class IntfSearchResponse(
  val success: Boolean = false,
  val results: List<IntfSearchItem> = emptyList(),
)

@Serializable
internal data class IntfSearchItem(
  @Serializable(with = FlexibleStringSerializer::class) val albumId: String = "",
  val title: String = "",
  val author: String? = null,
  val cover: String? = null,
  val intro: String? = null,
  @Serializable(with = FlexibleIntSerializer::class) val trackCount: Int = 0,
)

@Serializable
internal data class InterfacesResponse(
  val success: Boolean = false,
  val interfaces: List<OnlineSourceInfo> = emptyList(),
)

@Serializable
internal data class IntfAlbumListResponse(
  val success: Boolean = false,
  val error: String? = null,
  val tracks: List<IntfAlbumTrack> = emptyList(),
)

@Serializable
internal data class IntfAlbumTrack(
  @Serializable(with = FlexibleStringSerializer::class) val trackId: String = "",
  val title: String = "",
  @Serializable(with = FlexibleIntSerializer::class) val duration: Int = 0,
  @Serializable(with = FlexibleIntSerializer::class) val order: Int = 0,
)

@Serializable
internal data class IntfAudioResponse(
  val success: Boolean = false,
  val url: String = "",
  val error: String? = null,
)
