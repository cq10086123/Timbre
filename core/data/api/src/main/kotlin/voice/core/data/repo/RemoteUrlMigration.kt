package voice.core.data.repo

/**
 * Rewrites ids belonging to a remote server while preserving the rows that
 * contain playback state and bookmarks.
 */
public interface RemoteUrlMigration {

  /**
   * Replaces [oldPrefix] with [newPrefix] in remote book/chapter ids and
   * related progress and bookmark columns, atomically in Room.
   */
  public suspend fun migrate(
    oldPrefix: String,
    newPrefix: String,
  )
}
