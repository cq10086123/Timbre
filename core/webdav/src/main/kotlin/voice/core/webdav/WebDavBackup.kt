package voice.core.webdav

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * The shelf state that is worth keeping across devices: which books are on the
 * shelf and where playback stopped in each of them.
 *
 * Only books that exist on the current device are restored. A backup is not a
 * way to import books: the chapters and the audio files behind them stay a
 * local (or a WebDAV source) concern, see [WebDavBackupService.restore].
 */
@Serializable
public data class WebDavBackup(
  val version: Int = CURRENT_VERSION,
  val createdAt: String,
  val books: List<WebDavBackupBook>,
  /**
   * Shelf sections that do not live in the Room table, keyed by the
   * [voice.core.data.backup.ShelfBackupContributor.id] that wrote them.
   */
  val extras: Map<String, JsonElement> = emptyMap(),
) {

  public companion object {
    public const val CURRENT_VERSION: Int = 2

    /** Stored next to the server's base directory, so no collection has to be created. */
    public const val FILE_NAME: String = ".timbre-backup.json"

    /** Labelled backups sit next to the default one. */
    public const val FILE_PREFIX: String = ".timbre-backup-"

    public const val FILE_SUFFIX: String = ".json"

    public const val CONTENT_TYPE: String = "application/json"

    /** Longest label that still fits into a file name. */
    private const val MAX_LABEL_LENGTH: Int = 64

    /** The file [label] is written to; a blank label is the unnamed default backup. */
    public fun fileName(label: String?): String {
      val clean = label
        ?.trim()
        ?.replace('/', '_')
        ?.replace('\\', '_')
        ?.take(MAX_LABEL_LENGTH)
      return if (clean.isNullOrEmpty()) FILE_NAME else "$FILE_PREFIX$clean$FILE_SUFFIX"
    }

    /** The label a file name carries, null for the unnamed default backup. */
    public fun labelOf(fileName: String): String? {
      if (fileName == FILE_NAME) return null
      if (!fileName.startsWith(FILE_PREFIX) || !fileName.endsWith(FILE_SUFFIX)) return null
      return fileName.removePrefix(FILE_PREFIX).removeSuffix(FILE_SUFFIX).ifEmpty { null }
    }

    /** True for the default backup and every labelled one. */
    public fun isBackupFile(fileName: String): Boolean {
      return fileName == FILE_NAME || labelOf(fileName) != null
    }
  }
}

@Serializable
public data class WebDavBackupBook(
  val id: String,
  val name: String,
  val author: String? = null,
  /** Whether the book sits on the shelf. */
  val isActive: Boolean,
  val currentChapter: String,
  val positionInChapter: Long,
  val lastPlayedAt: String,
  val playbackSpeed: Float,
  val skipSilence: Boolean,
  val skipIntro: Long = 0L,
  val skipOutro: Long = 0L,
  val gain: Float = 0f,
  /** Matching hints for a device where the book id changed. */
  val chapterCount: Int,
  val totalDurationMs: Long,
)

/** Outcome of uploading the shelf to a WebDAV server. */
public sealed interface WebDavBackupResult {
  public class Success(
    public val bookCount: Int,
    public val serverName: String,
    public val label: String?,
  ) : WebDavBackupResult

  /** No WebDAV server is configured, or its password cannot be decrypted. */
  public data object NoServer : WebDavBackupResult

  /** A server is there, but no backup folder was picked yet. */
  public data object NoFolder : WebDavBackupResult

  /** The shelf holds nothing, so writing would only destroy the previous backup. */
  public data object EmptyShelf : WebDavBackupResult

  /** [count] books are still being indexed, their chapters are not stored yet. */
  public class BooksLoading(public val count: Int) : WebDavBackupResult

  public class Failure(public val reason: String) : WebDavBackupResult
}

/** Outcome of pulling a backup back into the local database. */
public sealed interface WebDavRestoreResult {
  public class Success(
    public val restored: Int,
    public val total: Int,
    public val serverName: String,
    public val label: String?,
    /**
     * Books the backup carries that live on a WebDAV server and are missing
     * here. Their chapters come from the server, so restore cannot put them
     * back: the source has to be added again, and only then can their progress
     * be merged. The ui says so instead of silently reporting a partial count.
     */
    public val missingWebDav: Int = 0,
  ) : WebDavRestoreResult

  public data object NoServer : WebDavRestoreResult

  /** A server is there, but no backup folder was picked yet. */
  public data object NoFolder : WebDavRestoreResult

  /** The server answered, but the backup file is not there. */
  public data object NoBackupFile : WebDavRestoreResult

  public class Failure(public val reason: String) : WebDavRestoreResult
}

/** One backup file found in the backup folder. */
public data class WebDavBackupItem(
  /** Null for the unnamed default backup. */
  val label: String?,
  val fileName: String,
  val lastModified: Long,
  val size: Long,
)

/** Outcome of listing the backups in the configured folder. */
public sealed interface WebDavBackupListResult {
  public class Success(
    public val items: List<WebDavBackupItem>,
    public val serverName: String,
  ) : WebDavBackupListResult

  public data object NoServer : WebDavBackupListResult

  public data object NoFolder : WebDavBackupListResult

  public class Failure(public val reason: String) : WebDavBackupListResult
}
