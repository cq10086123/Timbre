package voice.core.webdav

import kotlinx.serialization.Serializable

@Serializable
public data class WebDavServer(
  val id: String,
  val name: String,
  /** Base url without trailing slash, e.g. `https://nas.example.com:5006/dav`. */
  val baseUrl: String,
  val username: String,
  /** Keystore encrypted password, Base64 encoded. */
  val encryptedPassword: String,
  val trustAllCertificates: Boolean = false,
) {
  public constructor(
    id: String,
    name: String,
    baseUrl: String,
    username: String,
    encryptedPassword: String,
  ) : this(
    id = id,
    name = name,
    baseUrl = baseUrl,
    username = username,
    encryptedPassword = encryptedPassword,
    trustAllCertificates = false,
  )
}

@Serializable
public data class WebDavBookSource(
  val serverId: String,
  /** Full url of the registered folder or file on the server. */
  val url: String,
  val displayName: String,
  val mode: Mode,
  /**
   * The books this registration expanded into the last time the listing
   * worked. Used as a fallback when the server cannot be reached: a scan must
   * not deactivate already imported books just because one request failed.
   */
  val lastExpandedUrls: List<String> = emptyList(),
) {
  public enum class Mode {
    /** The registered url itself is one book. */
    SingleBook,

    /** Every child folder (or child audio file) of the registered url is a book. */
    LibraryRoot,
  }
}
