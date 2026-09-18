package voice.core.data.folders

import android.net.Uri
import kotlinx.coroutines.flow.Flow
import voice.core.data.BookId

/**
 * Book registrations that live outside the local documents provider, e.g.
 * WebDAV folders on a NAS. [AudiobookFolders] merges them into the shelf.
 */
public interface RemoteBookSources {

  /** The registered remote books. Library roots are already expanded. */
  public fun books(): Flow<List<DocumentFileWithUri>>

  /**
   * Removes the registration that produced [bookId]. Returns true when a
   * registration matched.
   */
  public suspend fun removeBookRegistration(bookId: BookId): Boolean

  public suspend fun hasAnyBooks(): Boolean
}
