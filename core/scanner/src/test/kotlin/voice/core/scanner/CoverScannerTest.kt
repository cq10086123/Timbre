package voice.core.scanner

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifySequence
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import org.junit.runner.RunWith
import voice.core.common.PlaybackIoGate
import voice.core.data.Book
import voice.core.data.BookContent
import voice.core.data.BookId
import voice.core.data.Chapter
import voice.core.data.ChapterId
import voice.core.data.MarkData
import java.io.File
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class CoverScannerTest {

  private val tmp = Files.createTempDirectory("coverScanner").toFile()

  private val coverSaver = mockk<CoverSaver> {
    coEvery { save(any(), any(), any()) } just Runs
    coEvery { newBookCoverFile() } returns File(tmp, "cover-${Uuid.random()}.png")
    coEvery { setBookCover(any(), any()) } just Runs
    every { hasUserChosenCover(any()) } returns false
  }
  private val coverExtractor = mockk<CoverExtractor> {
    coEvery { extractCover(any(), any()) } returns false
  }
  private val coverGenerator = mockk<CoverGenerator> {
    coEvery { create(any()) } returns mockk<Bitmap>()
  }

  private fun coverScanner(remoteCoverFinder: RemoteCoverFinder): CoverScanner = CoverScanner(
    context = mockk {
      every { filesDir } returns tmp
    },
    coverSaver = coverSaver,
    coverExtractor = coverExtractor,
    coverGenerator = coverGenerator,
    remoteCoverFinder = remoteCoverFinder,
    playbackIoGate = PlaybackIoGate(),
    semaphore = Semaphore(4),
  )

  @Test
  fun anAppliedFolderPictureSparesTheEmbeddedArtwork() {
    val remoteCoverFinder = mockk<RemoteCoverFinder> {
      coEvery { findAndSaveCover(any(), any()) } returns RemoteCoverLookup.Applied("marker")
    }
    val scanner = coverScanner(remoteCoverFinder)
    val book = remoteBook()

    runBlocking {
      scanner.scan(listOf(book))
      scanner.scan(listOf(book))
    }

    // the folder lookup runs on every scan so a picture added to the folder
    // later still wins, and the embedded artwork is never extracted
    coVerify(exactly = 2) { remoteCoverFinder.findAndSaveCoverIgnoringResult(book, any()) }
    coVerify(exactly = 0) { coverExtractor.extractCoverIgnoringResult(any<Uri>(), any<File>()) }
  }

  @Test
  fun aFolderWithoutPicturesFallsBackToTheEmbeddedArtwork() {
    val remoteCoverFinder = mockk<RemoteCoverFinder> {
      coEvery { findAndSaveCover(any(), any()) } returns RemoteCoverLookup.NoPictureInFolder
    }
    val scanner = coverScanner(remoteCoverFinder)
    val book = remoteBook()

    runBlocking {
      scanner.scan(listOf(book))
    }

    coVerify(exactly = 1) { coverExtractor.extractCoverIgnoringResult(any<Uri>(), any<File>()) }
  }

  @Test
  fun anInconclusiveLookupIsRetriedOnTheNextScan() {
    val remoteCoverFinder = mockk<RemoteCoverFinder> {
      coEvery { findAndSaveCover(any(), any()) } returns RemoteCoverLookup.Inconclusive
    }
    val scanner = coverScanner(remoteCoverFinder)
    val book = remoteBook()

    runBlocking {
      scanner.scan(listOf(book))
      scanner.scan(listOf(book))
    }

    coVerify(exactly = 2) { remoteCoverFinder.findAndSaveCoverIgnoringResult(book, any()) }
  }

  @Test
  fun theAppliedPictureMarkerIsReusedAndClearedWithTheFolderAnswer() {
    val cover = File(tmp, "stored-cover.png").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
    val book = remoteBook(cover = cover)
    val appliedMarker = "https://nas.local/books/one/cover.jpg|100|200"
    val remoteCoverFinder = mockk<RemoteCoverFinder> {
      coEvery { findAndSaveCover(any(), any()) } returnsMany listOf(
        RemoteCoverLookup.Applied(appliedMarker),
        RemoteCoverLookup.Inconclusive,
        RemoteCoverLookup.NoPictureInFolder,
        RemoteCoverLookup.Applied(appliedMarker),
      )
    }
    val scanner = coverScanner(remoteCoverFinder)

    runBlocking {
      repeat(4) { scanner.scan(listOf(book)) }
    }

    coVerifySequence {
      // no marker yet, the lookup starts without a hint
      remoteCoverFinder.findAndSaveCoverIgnoringResult(book, null)
      // the applied marker is kept across an inconclusive lookup...
      remoteCoverFinder.findAndSaveCoverIgnoringResult(book, appliedMarker)
      // ...and only a definitive "no picture" clears it
      remoteCoverFinder.findAndSaveCoverIgnoringResult(book, appliedMarker)
      remoteCoverFinder.findAndSaveCoverIgnoringResult(book, null)
    }
  }

  @Test
  fun localBooksNeverTouchTheRemoteLookup() {
    val remoteCoverFinder = mockk<RemoteCoverFinder>()
    val scanner = coverScanner(remoteCoverFinder)
    val book = localBook()

    runBlocking {
      scanner.scan(listOf(book))
    }

    coVerify(exactly = 0) { remoteCoverFinder.findAndSaveCoverIgnoringResult(any(), any()) }
    coVerify(exactly = 1) { coverExtractor.extractCoverIgnoringResult(any<Uri>(), any<File>()) }
  }

  @Test
  fun aUserChosenCoverIsNeverReplacedByTheFolderPicture() {
    every { coverSaver.hasUserChosenCover(any()) } returns true
    val remoteCoverFinder = mockk<RemoteCoverFinder> {
      coEvery { findAndSaveCover(any(), any()) } returns RemoteCoverLookup.Applied("marker")
    }
    val scanner = coverScanner(remoteCoverFinder)
    val book = remoteBook()

    runBlocking {
      scanner.scan(listOf(book))
      scanner.scan(listOf(book))
    }

    coVerify(exactly = 0) { remoteCoverFinder.findAndSaveCoverIgnoringResult(any(), any()) }
    coVerify(exactly = 0) { coverExtractor.extractCoverIgnoringResult(any<Uri>(), any<File>()) }
    coVerify(exactly = 0) { coverSaver.setBookCover(any(), any()) }
  }

  private fun remoteBook(cover: File? = null): Book = book(id = "https://nas.local/books/one", cover = cover)

  private fun localBook(): Book = book(id = "content://books/local")

  private fun book(
    id: String,
    cover: File? = null,
  ): Book {
    val chapters = listOf(
      Chapter(
        id = ChapterId("$id/1.mp3"),
        duration = 5.minutes.inWholeMilliseconds,
        fileLastModified = Instant.EPOCH,
        markData = listOf(MarkData(startMs = 0L, name = "Chapter 1")),
        name = "name",
        fileSize = 0,
      ),
    )
    return Book(
      content = BookContent(
        author = Uuid.random().toString(),
        name = "Book",
        positionInChapter = 0L,
        playbackSpeed = 1F,
        addedAt = Instant.EPOCH,
        chapters = chapters.map { it.id },
        cover = cover,
        currentChapter = chapters.first().id,
        isActive = true,
        lastPlayedAt = Instant.EPOCH,
        skipSilence = false,
        id = BookId(id),
        gain = 0F,
        genre = null,
        narrator = null,
        series = null,
        part = null,
      ),
      chapters = chapters,
    )
  }

  // the checker treats the finder's result as must-use; mockk verification
  // lambdas are Unit-typed, so verification goes through this wrapper
  @IgnorableReturnValue
  private suspend fun RemoteCoverFinder.findAndSaveCoverIgnoringResult(
    book: Book,
    alreadyApplied: String?,
  ): RemoteCoverLookup = findAndSaveCover(book, alreadyApplied)

  @IgnorableReturnValue
  private suspend fun CoverExtractor.extractCoverIgnoringResult(
    input: Uri,
    outputFile: File,
  ): Boolean = extractCover(input, outputFile)
}
