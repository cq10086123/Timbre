package voice.core.webdav

public data class WebDavResource(
  /** Full url of the resource, still percent encoded. */
  val url: String,
  /** Human readable name, decoded. */
  val name: String,
  val isDirectory: Boolean,
  val contentLength: Long,
  val lastModified: Long,
)
