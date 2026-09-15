package voice.core.data.folders

import android.net.Uri
import kotlinx.coroutines.flow.Flow
import voice.core.data.BookId
import voice.core.documentfile.CachedDocumentFile

public interface AudiobookFolders {
  public fun all(): Flow<Map<FolderType, List<DocumentFileWithUri>>>

  public suspend fun add(
    uri: Uri,
    type: FolderType,
  )

  public suspend fun remove(
    uri: Uri,
    folderType: FolderType,
  )

  public suspend fun hasAnyFolders(): Boolean

  /**
   * Removes the shelf entry (folder or file registration) that produced the
   * given book, so a deleted book isn't scanned back in forever.
   */
  public suspend fun removeBookRegistration(bookId: BookId)

  /**
   * One-time migration of the legacy Root/Author folder structures into the
   * bookshelf model where every registered folder is a single book.
   */
  public suspend fun migrateLegacyFolders()
}

public data class DocumentFileWithUri(
  val documentFile: CachedDocumentFile,
  val uri: Uri,
)
