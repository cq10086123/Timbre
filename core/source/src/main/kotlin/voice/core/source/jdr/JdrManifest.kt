package voice.core.source.jdr

import kotlinx.serialization.Serializable

/** Describes one jdr package. Lives at `manifest.json` inside the zip. */
@Serializable
public data class JdrManifest(
  /** Bump when the runtime contract breaks. The app refuses other versions. */
  val formatVersion: Int = FORMAT_VERSION,
  /** Stable package identifier, reused on update. */
  val packageId: String,
  val name: String,
  val version: String = "",
  /** Zip entry holding the obfuscated javascript bundle. */
  val entry: String = DEFAULT_ENTRY,
  /** Declared sources; the bundle must register every one of them. */
  val sources: List<JdrSourceInfo> = emptyList(),
) {
  public companion object {
    public const val FORMAT_VERSION: Int = 1
    public const val DEFAULT_ENTRY: String = "bundle.js"
  }
}

/** One source declared in [JdrManifest.sources]. */
@Serializable
public data class JdrSourceInfo(
  /** Source id, unique within the package. */
  val id: String,
  /** Display name shown in the search chips. */
  val name: String,
)
