package voice.core.data.folders

import androidx.core.net.toUri
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import voice.core.documentfile.FileBasedDocumentFile
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
class LegacyFolderExpanderTest {

  @get:Rule
  val temporaryFolder = TemporaryFolder()

  @Test
  fun rootExpandsIntoChildFoldersAndFiles() = kotlinx.coroutines.test.runTest {
    val root = temporaryFolder.newFolder("audiobooks")
    val book1 = File(root, "Book1").apply { mkdirs() }
    File(book1, "1.mp3").createNewFile()
    val book2 = File(root, "Book2").apply { mkdirs() }
    File(book2, "1.mp3").createNewFile()
    val looseFile = File(root, "loose.mp3").apply { createNewFile() }

    val entries = LegacyFolderExpander.expand(
      FolderType.Root,
      FileBasedDocumentFile(root),
    )

    assertEquals(
      expected = setOf(
        LegacyBookEntry(FolderType.SingleFolder, book1.toUri()),
        LegacyBookEntry(FolderType.SingleFolder, book2.toUri()),
        LegacyBookEntry(FolderType.SingleFile, looseFile.toUri()),
      ),
      actual = entries.toSet(),
    )
  }

  @Test
  fun authorExpandsNestedBooksAndLooseFiles() = kotlinx.coroutines.test.runTest {
    val root = temporaryFolder.newFolder("audiobooks")
    val looseFile = File(root, "loose.mp3").apply { createNewFile() }
    val author = File(root, "Author").apply { mkdirs() }
    val authorLooseFile = File(author, "single.mp3").apply { createNewFile() }
    val book = File(author, "Book").apply { mkdirs() }
    File(book, "1.mp3").createNewFile()

    val entries = LegacyFolderExpander.expand(
      FolderType.Author,
      FileBasedDocumentFile(root),
    )

    assertEquals(
      expected = setOf(
        LegacyBookEntry(FolderType.SingleFile, looseFile.toUri()),
        LegacyBookEntry(FolderType.SingleFile, authorLooseFile.toUri()),
        LegacyBookEntry(FolderType.SingleFolder, book.toUri()),
      ),
      actual = entries.toSet(),
    )
  }

  @Test
  fun singleFolderStaysUntouched() = kotlinx.coroutines.test.runTest {
    val folder = temporaryFolder.newFolder("book")
    val entries = LegacyFolderExpander.expand(
      FolderType.SingleFolder,
      FileBasedDocumentFile(folder),
    )
    assertEquals(
      expected = listOf(LegacyBookEntry(FolderType.SingleFolder, folder.toUri())),
      actual = entries,
    )
  }
}
