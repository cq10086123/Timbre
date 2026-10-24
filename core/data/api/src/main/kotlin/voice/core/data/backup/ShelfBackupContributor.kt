package voice.core.data.backup

import kotlinx.serialization.json.JsonElement

/**
 * A part of the shelf that does not live in the Room book table and therefore
 * has to be saved and restored by whoever writes a backup.
 *
 * Books added from an online source are the reason this exists: they are kept
 * in their own DataStore, so a backup that only walks [voice.core.data.repo.BookRepository]
 * would silently drop them.
 */
public interface ShelfBackupContributor {

  /** Stable key of this section inside the backup file. */
  public val id: String

  /** Current contents plus how many items they hold. */
  public suspend fun save(): ShelfBackupContribution

  /** Merges [payload] back in and reports what happened. */
  public suspend fun restore(payload: JsonElement): ShelfBackupRestore
}

/** What [ShelfBackupContributor.save] wrote. */
public data class ShelfBackupContribution(
  /** Items written, for the "backed up N books" message. */
  val count: Int,
  val payload: JsonElement,
)

/** What [ShelfBackupContributor.restore] merged. */
public data class ShelfBackupRestore(
  val restored: Int,
  /** Items the payload held, restored or not. */
  val total: Int,
)
