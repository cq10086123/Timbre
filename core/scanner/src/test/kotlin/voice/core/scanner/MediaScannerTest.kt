package voice.core.scanner

import androidx.core.net.toFile
import androidx.core.net.toUri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import voice.core.common.PlaybackIoGate
import voice.core.data.BookContent
import voice.core.data.BookId
import voice.core.data.ChapterId
import voice.core.data.folders.FolderType
import voice.core.data.repo.BookContentRepo
import voice.core.data.repo.BookContentRepoImpl
import voice.core.data.repo.BookRepositoryImpl
import voice.core.data.repo.ChapterRepoImpl
import voice.core.data.repo.internals.AppDb
import voice.core.data.toUri
import voice.core.documentfile.FileBasedDocumentFactory
import voice.core.documentfile.FileBasedDocumentFile
import java.io.Closeable
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class MediaScannerTest {

  @Test
  fun singleFileDeletion() = test {
    val audiobookFolder = folder("audiobooks")

    val book1 = File(audiobookFolder, "book1")
    val book1Chapters = listOf(
      audioFile(book1, "1.mp3"),
      audioFile(book1, "2.mp3"),
      audioFile(book1, "10.mp3"),
    )

    // every imported folder is one book regardless of the legacy folder type
    scan(FolderType.Root, audiobookFolder)

    book1Chapters.first().delete()

    scan(FolderType.Root, audiobookFolder)

    assertBookContents(
      BookContentView(
        id = audiobookFolder,
        chapters = book1Chapters.drop(1),
      ),
    )
  }

  @Test
  fun metadataPreservedOnDeletion() = test {
    val audiobookFolder = folder("audiobooks")

    val book1 = File(audiobookFolder, "book1")
    val bookId = BookId(audiobookFolder.toUri())
    val book1Chapters = listOf(
      audioFile(book1, "1.mp3"),
      audioFile(book1, "2.mp3"),
      audioFile(book1, "10.mp3"),
    )

    scan(FolderType.Root, audiobookFolder)

    val contentWithPositionAtLastChapter =
      bookContentRepo.get(BookId(audiobookFolder.toUri()))!!.copy(currentChapter = ChapterId(book1Chapters.last().toUri()))
    bookContentRepo.put(contentWithPositionAtLastChapter)

    book1Chapters.forEach { it.toUri().toFile().delete() }

    scan(FolderType.Root, audiobookFolder)

    audioFile(book1, "1.mp3")
    audioFile(book1, "2.mp3")
    audioFile(book1, "10.mp3")

    assertEquals(expected = contentWithPositionAtLastChapter, actual = bookContentRepo.get(bookId))
  }

  @Test
  fun multipleRoots() = test {
    val audiobookFolder1 = folder("shelf1")

    val topFileBook = audioFile(parent = audiobookFolder1, "test.mp3")
    val book1 = File(audiobookFolder1, "book1")
    val book1Chapters = listOf(
      audioFile(book1, "1.mp3"),
      audioFile(book1, "2.mp3"),
      audioFile(book1, "10.mp3"),
    )

    val audiobookFolder2 = folder("shelf2")
    val book2 = File(audiobookFolder2, "book2")
    val book2Chapters = listOf(audioFile(book2, "1.mp3"))

    scan(FolderType.Root, audiobookFolder1, audiobookFolder2)

    assertBookContents(
      BookContentView(audiobookFolder1, chapters = book1Chapters + listOf(topFileBook)),
      BookContentView(audiobookFolder2, chapters = book2Chapters),
    )
  }

  @Test
  fun importedFolderIsOneBookWithNestedFolders() = test {
    val audiobookFolder = folder("audiobooks1")

    val topFileBook = audioFile(parent = audiobookFolder, "test.mp3")

    val book1 = File(audiobookFolder, "book1")
    val book1Chapters = listOf(
      audioFile(book1, "1.mp3"),
      audioFile(book1, "2.mp3"),
      audioFile(book1, "10.mp3"),
    )

    val book2 = File(audiobookFolder, "book2")
    val book2Chapters = listOf(
      audioFile(book2, "1.mp3"),
      audioFile(book2, "2.mp3"),
      audioFile(book2, "10.mp3"),
    )

    scan(FolderType.Root, audiobookFolder)

    assertBookContents(
      BookContentView(
        audiobookFolder,
        chapters = book1Chapters + book2Chapters + listOf(topFileBook),
      ),
    )
  }

  @Test
  fun scanSingleFile() = test {
    val book = audioFile(parent = folder("audiobooks1"), "test.mp3")
    scan(FolderType.SingleFile, book)
    assertBookContents(
      BookContentView(book, chapters = listOf(book)),
    )
  }

  @Test
  fun scanSingleFolder() = test {
    val folder = folder("book")
    val book = audioFile(parent = folder, "test.mp3")
    scan(FolderType.SingleFolder, folder)
    assertBookContents(
      BookContentView(folder, chapters = listOf(book)),
    )
  }

  @Test
  fun reportsProgressPerBookWhileScanning() = test {
    val shelf1 = folder("shelf1")
    val shelf2 = folder("shelf2")
    audioFile(parent = shelf1, "1.mp3")
    audioFile(parent = shelf1, "2.mp3")
    audioFile(parent = shelf1, "10.mp3")
    audioFile(parent = shelf2, "1.mp3")

    scan(FolderType.SingleFolder, shelf1, shelf2)

    // both books were reported with their own chapter counts while they were
    // analyzed...
    val shelf1Progress = analyzeSnapshots.mapNotNull { it[BookId(shelf1.toUri())] }
    val shelf2Progress = analyzeSnapshots.mapNotNull { it[BookId(shelf2.toUri())] }
    assertTrue(shelf1Progress.isNotEmpty())
    assertTrue(shelf2Progress.isNotEmpty())
    assertTrue(shelf1Progress.all { it.chaptersTotal == 3 })
    assertTrue(shelf2Progress.all { it.chaptersTotal == 1 })
    assertTrue(shelf1Progress.all { it.chaptersScanned in 0..3 })
    // ...and no book is reported as importing anymore once the scan is done
    assertEquals(expected = emptyMap(), actual = bookScanProgress)
  }

  @Test
  fun storesChaptersWhileScanning() = test {
    // more files than one parse batch, so the book is published before the
    // last chapter was analyzed
    val folder = folder("bigBook")
    val files = (1..45).map { audioFile(parent = folder, name = "$it.mp3") }

    scan(FolderType.SingleFolder, folder)

    val stored = storedContents
    assertTrue(stored.size >= 2)
    val importedChapters = stored.last().chapters
    assertEquals(expected = files.size, actual = importedChapters.size)
    stored.forEach { content ->
      // the book grows chapter by chapter and always starts with its first
      // chapter, so it can already be played while it is imported
      assertEquals(
        expected = content.chapters,
        actual = importedChapters.take(content.chapters.size),
      )
      assertEquals(expected = importedChapters.first(), actual = content.currentChapter)
    }
    stored.zipWithNext().forEach { (before, after) ->
      assertTrue(after.chapters.size > before.chapters.size)
    }
    assertBookContents(
      BookContentView(
        folder,
        chapters = importedChapters.map { it.toUri().toFile() },
      ),
    )
  }

  @Test
  fun newBookReusesFirstChapterMetadata() = test {
    val folder = folder("book")
    audioFile(parent = folder, "1.mp3")
    audioFile(parent = folder, "2.mp3")

    scan(FolderType.SingleFolder, folder)

    assertEquals(expected = 2, actual = analyzeCalls)
  }

  @Test
  fun authorFolderIsOneBook() = test {
    val audioBooks = folder("audiobooks")

    val book1 = audioFile(parent = audioBooks, "test.mp3")
    val book2 = audioFile(parent = audioBooks, "author1/test.mp3")

    val book3 = File(audioBooks, "author1/book1")
    val book3Chapter1 = audioFile(book3, "c1.mp3")
    val book3Chapter2 = audioFile(book3, "c2.mp3")

    val book4 = File(audioBooks, "author1/book2")
    val book4Chapter1 = audioFile(book4, "a.mp3")

    scan(FolderType.Author, audioBooks)
    // ordered segment-by-segment: book1/*, book2/*, then files directly in
    // the author folder and the root folder
    assertBookContents(
      BookContentView(
        audioBooks,
        chapters = listOf(
          book3Chapter1,
          book3Chapter2,
          book4Chapter1,
          book2,
          book1,
        ),
      ),
    )
  }

  @Test
  fun folderScannedOnce() = test {
    val audioBooks = folder("audiobooks")

    audioFile(parent = audioBooks, "test.mp3")
    audioFile(parent = audioBooks, "author1/test.mp3")
    audioFile(parent = File(audioBooks, "author1/book1"), "c1.mp3")
    audioFile(parent = File(audioBooks, "author1/book1"), "c2.mp3")
    audioFile(parent = File(audioBooks, "author1/book2"), "a.mp3")

    scan(FolderType.Author, audioBooks)

    assertEquals(expected = 1, actual = scannedBooks.size)
  }

  private fun test(test: suspend TestEnvironment.() -> Unit) {
    runTest {
      TestEnvironment().use { test(it) }
    }
  }

  private class TestEnvironment : Closeable {

    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDb::class.java)
      .allowMainThreadQueries()
      .build()
    val bookContentRepo = BookContentRepoImpl(db.bookContentDao())
    private val chapterRepo = ChapterRepoImpl(db.chapterDao())
    private val mediaAnalyzer = mockk<MediaAnalyzer>()
    private val analyzeCounter = java.util.concurrent.atomic.AtomicInteger(0)
    val analyzeCalls: Int get() = analyzeCounter.get()
    val analyzeSnapshots: MutableList<Map<BookId, BookScanProgress>> =
      java.util.Collections.synchronizedList(mutableListOf())
    private val scannedRepo = ScannedBooksRecordingRepo(bookContentRepo)
    val scannedBooks: List<BookId> get() = scannedRepo.scannedBooks
    val storedContents: List<BookContent> get() = scannedRepo.storedContents
    private val scanProgressReporter = ScanProgressReporter()
    val bookScanProgress: Map<BookId, BookScanProgress> get() = scanProgressReporter.bookProgress.value
    private val scanner = MediaScanner(
      contentRepo = scannedRepo,
      chapterParser = ChapterParser(
        chapterRepo = chapterRepo,
        mediaAnalyzer = mediaAnalyzer,
        scanProgressReporter = scanProgressReporter,
        playbackIoGate = PlaybackIoGate(),
        analyzeSemaphore = Semaphore(4),
      ),
      bookParser = BookParser(
        contentRepo = bookContentRepo,
        mediaAnalyzer = mediaAnalyzer,
        fileFactory = FileBasedDocumentFactory,
      ),
      deviceHasPermissionBug = mockk(),
      scanProgressReporter = scanProgressReporter,
      semaphore = Semaphore(4),
    )

    val bookRepo = BookRepositoryImpl(chapterRepo, bookContentRepo)

    private val root: File = Files.createTempDirectory(this::class.java.canonicalName!!).toFile()

    suspend fun scan(
      type: FolderType = FolderType.Root,
      vararg roots: File,
    ) {
      scanner.scan(mapOf(type to roots.map(::FileBasedDocumentFile)))
    }

    @IgnorableReturnValue
    fun audioFile(
      parent: File,
      name: String,
    ): File {
      check(name.endsWith(".mp3"))
      return File(parent, name)
        .also {
          it.parentFile?.mkdirs()
          check(it.createNewFile())
        }
        .also {
          coEvery { mediaAnalyzer.analyze(any()) } coAnswers {
            analyzeSnapshots += scanProgressReporter.bookProgress.value
            analyzeCounter.incrementAndGet()
            Metadata(
              duration = 1000L,
              artist = "Author",
              album = "Book Name",
              fileName = "Chapter",
              chapters = emptyList(),
              title = "Title",
              genre = "Genre",
              narrator = "Narrator",
              series = "Series",
              part = "Part",
            )
          }
        }
    }

    fun folder(name: String): File {
      return File(root, name)
        .also { it.mkdirs() }
    }

    suspend fun assertBookContents(vararg expected: BookContentView) {
      bookRepo.all()
        .map {
          BookContentView(
            id = it.id.toUri().toFile(),
            chapters = it.content.chapters.map { chapter ->
              chapter.toUri().toFile()
            },
          )
        }
        .let { actual ->
          assertEquals(
            expected = expected.sortedBy { it.id },
            actual = actual.sortedBy { it.id },
          )
        }
    }

    override fun close() {
      root.delete()
    }
  }

  data class BookContentView(
    val id: File,
    val chapters: List<File>,
  )

  private class ScannedBooksRecordingRepo(private val delegate: BookContentRepo) : BookContentRepo by delegate {

    val scannedBooks = mutableListOf<BookId>()
    val storedContents = mutableListOf<BookContent>()

    override suspend fun setAllInactiveExcept(ids: List<BookId>) {
      scannedBooks += ids
      delegate.setAllInactiveExcept(ids)
    }

    override suspend fun put(
      content: BookContent,
      persist: Boolean,
    ) {
      storedContents += content
      delegate.put(content, persist)
    }
  }
}
