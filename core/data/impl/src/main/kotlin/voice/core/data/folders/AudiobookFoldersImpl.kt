package voice.core.data.folders

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.datastore.core.DataStore
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import voice.core.analytics.api.Analytics
import voice.core.data.BookId
import voice.core.documentfile.CachedDocumentFile
import voice.core.documentfile.CachedDocumentFileFactory
import voice.core.logging.api.Logger

@ContributesBinding(AppScope::class)
public class AudiobookFoldersImpl
internal constructor(
  @RootAudiobookFoldersStore
  private val rootAudioBookFoldersStore: DataStore<Set<@JvmSuppressWildcards Uri>>,
  @SingleFolderAudiobookFoldersStore
  private val singleFolderAudiobookFoldersStore: DataStore<Set<@JvmSuppressWildcards Uri>>,
  @SingleFileAudiobookFoldersStore
  private val singleFileAudiobookFoldersStore: DataStore<Set<@JvmSuppressWildcards Uri>>,
  @AuthorAudiobookFoldersStore
  private val authorAudiobookFoldersStore: DataStore<Set<@JvmSuppressWildcards Uri>>,
  @BookshelfMigrationDoneStore
  private val bookshelfMigrationDoneStore: DataStore<Boolean>,
  private val context: Context,
  private val cachedDocumentFileFactory: CachedDocumentFileFactory,
  private val analytics: Analytics,
  private val persistedUriPermissions: PersistedUriPermissions,
) : AudiobookFolders {

  public override fun all(): Flow<Map<FolderType, List<DocumentFileWithUri>>> {
    val flows = FolderType.entries
      .map { folderType ->
        dataStore(folderType).data.map { uris ->
          val persistedUris = persistedUriPermissions.persistedUris()
          val documentFiles = uris
            .filter { it.isAccessibleViaPersistedPermission(persistedUris) }
            .map { uri ->
              DocumentFileWithUri(
                documentFile = uri.toDocumentFile(folderType),
                uri = uri,
              )
            }
          folderType to documentFiles
        }
      }
    return combine(flows) { it.toMap() }
  }

  private fun Uri.toDocumentFile(folderType: FolderType): CachedDocumentFile {
    val uri = when (folderType) {
      FolderType.SingleFile -> this
      FolderType.SingleFolder,
      FolderType.Root,
      FolderType.Author,
      -> {
        if (pathSegments.contains("document")) {
          // migrated entries keep the exact document uri the scanner used to
          // use as the book id, so listening progress is preserved
          this
        } else {
          DocumentsContract.buildDocumentUriUsingTree(
            this,
            DocumentsContract.getTreeDocumentId(this),
          )
        }
      }
    }
    return cachedDocumentFileFactory.create(uri)
  }

  /**
   * A migrated child entry is not persisted on its own; it is covered by the
   * persisted permission of its ancestor tree.
   */
  private fun Uri.isAccessibleViaPersistedPermission(persistedUris: Set<Uri>): Boolean {
    if (this in persistedUris) return true
    return runCatching {
      val documentId = if (pathSegments.contains("tree")) {
        DocumentsContract.getTreeDocumentId(this)
      } else {
        DocumentsContract.getDocumentId(this)
      }
      persistedUris.any { granted ->
        val grantedId = runCatching { DocumentsContract.getTreeDocumentId(granted) }.getOrNull()
        grantedId != null && (documentId == grantedId || documentId.startsWith("$grantedId/"))
      }
    }.getOrDefault(false)
  }

  public override suspend fun add(
    uri: Uri,
    type: FolderType,
  ) {
    analytics.event("add_folder", mapOf("type" to type.name))
    try {
      context.contentResolver.takePersistableUriPermission(
        uri,
        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
      )
    } catch (_: SecurityException) {
      Logger.w("Could not take uri permission for $uri")
    }
    dataStore(type).updateData {
      it + uri
    }
  }

  public override suspend fun remove(
    uri: Uri,
    folderType: FolderType,
  ) {
    analytics.event("remove_folder", mapOf("type" to folderType.name))
    try {
      context.contentResolver.releasePersistableUriPermission(
        uri,
        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
      )
    } catch (_: SecurityException) {
      // migrated child folders share the permission of their ancestor tree and
      // therefore can't release it individually
      Logger.w("Could not release uri permission for $uri")
    }
    dataStore(folderType).updateData { folders ->
      folders - uri
    }
  }

  override suspend fun removeBookRegistration(bookId: BookId) {
    val bookDocumentId = runCatching {
      DocumentsContract.getDocumentId(bookId.toUri())
    }.getOrNull() ?: return

    FolderType.entries.forEach { type ->
      val matching = dataStore(type).data.first()
        .filter { uri ->
          val registeredId = runCatching { uri.registeredDocumentId() }.getOrNull()
          registeredId != null && registeredId == bookDocumentId
        }
      matching.forEach { uri ->
        remove(uri, type)
      }
    }
  }

  private fun Uri.registeredDocumentId(): String {
    return when {
      // migrated entries are document uris nested inside an ancestor tree
      pathSegments.contains("document") -> DocumentsContract.getDocumentId(this)
      pathSegments.contains("tree") -> DocumentsContract.getTreeDocumentId(this)
      else -> DocumentsContract.getDocumentId(this)
    }
  }

  override suspend fun migrateLegacyFolders() {
    if (bookshelfMigrationDoneStore.data.first()) return
    try {
      val rootUris = rootAudioBookFoldersStore.data.first()
      val authorUris = authorAudiobookFoldersStore.data.first()
      if (rootUris.isNotEmpty() || authorUris.isNotEmpty()) {
        val folders = singleFolderAudiobookFoldersStore.data.first().toMutableSet()
        val files = singleFileAudiobookFoldersStore.data.first().toMutableSet()

        var failed = false
        rootUris.forEach { uri ->
          if (!uri.expandLegacy(FolderType.Root, folders, files)) failed = true
        }
        authorUris.forEach { uri ->
          if (!uri.expandLegacy(FolderType.Author, folders, files)) failed = true
        }

        // leave the legacy registration untouched so the migration is retried
        // on the next scan instead of losing books from an unreadable folder
        if (failed) return

        singleFolderAudiobookFoldersStore.updateData { folders.toSet() }
        singleFileAudiobookFoldersStore.updateData { files.toSet() }
        rootAudioBookFoldersStore.updateData { emptySet() }
        authorAudiobookFoldersStore.updateData { emptySet() }
      }
      bookshelfMigrationDoneStore.updateData { true }
    } catch (e: Exception) {
      // keep the migration pending so it is retried on the next scan
      Logger.w(e, "Could not migrate legacy folders")
    }
  }

  /** Returns false when the legacy folder couldn't be enumerated. */
  private fun Uri.expandLegacy(
    type: FolderType,
    targetFolders: MutableSet<Uri>,
    targetFiles: MutableSet<Uri>,
  ): Boolean {
    return runCatching {
      val rootDocument = cachedDocumentFileFactory.create(
        DocumentsContract.buildDocumentUriUsingTree(
          this,
          DocumentsContract.getTreeDocumentId(this),
        ),
      )
      LegacyFolderExpander.expand(type, rootDocument).forEach { entry ->
        when (entry.type) {
          FolderType.SingleFolder -> targetFolders += entry.uri
          FolderType.SingleFile -> targetFiles += entry.uri
          FolderType.Root, FolderType.Author -> Unit
        }
      }
      true
    }.getOrElse {
      Logger.w(it, "Could not expand legacy $type folder $this")
      false
    }
  }

  private fun dataStore(type: FolderType): DataStore<Set<Uri>> {
    return when (type) {
      FolderType.SingleFile -> singleFileAudiobookFoldersStore
      FolderType.SingleFolder -> singleFolderAudiobookFoldersStore
      FolderType.Root -> rootAudioBookFoldersStore
      FolderType.Author -> authorAudiobookFoldersStore
    }
  }

  public override suspend fun hasAnyFolders(): Boolean {
    return FolderType.entries.any {
      dataStore(it).data.first().isNotEmpty()
    }
  }
}
