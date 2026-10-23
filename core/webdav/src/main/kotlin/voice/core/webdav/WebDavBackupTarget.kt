package voice.core.webdav

import kotlinx.serialization.Serializable

/**
 * Where the shelf backup lives: a server and the folder on it.
 *
 * A server alone is not enough - the folder is picked explicitly in the
 * settings, so a backup never lands in a place the user did not choose.
 */
@Serializable
public data class WebDavBackupTarget(
  val serverId: String? = null,
  val folderUrl: String? = null,
  /** Label of the last backup that was written, offered again next time. */
  val label: String? = null,
) {

  /** True once a folder was picked on a server. */
  public val isConfigured: Boolean
    get() = serverId != null && !folderUrl.isNullOrBlank()
}

/** [WebDavBackupTarget] resolved against the servers that are configured now. */
public data class WebDavBackupLocation(
  val serverId: String,
  val serverName: String,
  val serverBaseUrl: String,
  val folderUrl: String,
)

/** Whether a backup can run: a server plus a folder the user picked. */
public enum class WebDavBackupTargetStatus {
  Ready,
  NoServer,
  NoFolder,
}
