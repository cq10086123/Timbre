package voice.core.data.folders

import voice.core.data.isAudioFile
import voice.core.documentfile.CachedDocumentFile

/**
 * Expands a legacy [FolderType.Root] / [FolderType.Author] folder into the
 * individual books it used to produce, so they can be re-registered as plain
 * bookshelf folders/files.
 */
public object LegacyFolderExpander {

  public fun expand(
    type: FolderType,
    root: CachedDocumentFile,
  ): List<LegacyBookEntry> {
    return when (type) {
      FolderType.Root -> root.children.mapNotNull { it.asBookEntry() }
      FolderType.Author -> root.children.flatMap { author ->
        if (author.isAudioFile()) {
          listOf(LegacyBookEntry(FolderType.SingleFile, author.uri))
        } else {
          author.children.mapNotNull { it.asBookEntry() }
        }
      }
      FolderType.SingleFile, FolderType.SingleFolder -> {
        listOf(LegacyBookEntry(type, root.uri))
      }
    }
  }

  private fun CachedDocumentFile.asBookEntry(): LegacyBookEntry? {
    return when {
      isAudioFile() -> LegacyBookEntry(FolderType.SingleFile, uri)
      isDirectory -> LegacyBookEntry(FolderType.SingleFolder, uri)
      else -> null
    }
  }
}

public data class LegacyBookEntry(
  val type: FolderType,
  val uri: android.net.Uri,
)
