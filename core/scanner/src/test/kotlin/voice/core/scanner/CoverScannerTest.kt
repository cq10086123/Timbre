package voice.core.scanner

import android.content.Context
import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
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
    coEvery { save(any(), any()) } just Runs
    coEvery { newBookCoverFile() } returns File(tmp, "cover-${Uuid.random()}.png")
    coEvery { setBookCover(any(), any()) } just Runs
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
  fun remoteFolderLookupRunsOncePerBook() {
    val remoteCoverFinder = mockk<RemoteCoverFinder> {
      coEvery { findAndSaveCover(any()) } returns true
    }
    val scanner = coverScanner(remoteCoverFinder)
    val book = remoteBook()

    runBlocking {
      scanner.scan(listOf(book))
      scanner.scan(listOf(book))
    }

    coVerify(exactly = 1) { remoteCoverFinder.findAndSaveCover(book) }
  }

  @Test
  fun remoteFolderWithoutPicturesIsOnlyListedOnce() {
    val remoteCoverFinder = mockk<RemoteCoverFinder> {
      coEvery { findAndSaveCover(any()) } returns false
    }
    val scanner = coverScanner(remoteCoverFinder)
    val book = remoteBook()

    runBlocking {
      scanner.scan(listOf(book))
      scanner.scan(listOf(book))
    }

    coVerify(exactly = 1) { remoteCoverFinder.findAndSaveCover(book) }
  }

  @Test
  fun unreachableRemoteFolderIsRetriedOnTheNextScan() {
    val remoteCoverFinder = mockk<RemoteCoverFinder> {
      coEvery { findAndSaveCover(any()) } returns null
    }
    val scanner = coverScanner(remoteCoverFinder)
    val book = remoteBook()

    runBlocking {
      scanner.scan(listOf(book))
      scanner.scan(listOf(book))
    }

    coVerify(exactly = 2) { remoteCoverFinder.findAndSaveCover(book) }
  }

  @Test
  fun localBooksNeverTouchTheRemoteLookup() {
    val remoteCoverFinder = mockk<RemoteCoverFinder>()
    val scanner = coverScanner(remoteCoverFinder)
    val book = localBook()

    runBlocking {
      scanner.scan(listOf(book))
    }

    coVerify(exactly = 0) { remoteCoverFinder.findAndSaveCover(any()) }
    coVerify(exactly = 1) { coverExtractor.extractCover(any(), any()) }
  }

  private fun remoteBook(): Book = book(id = "https://nas.local/books/one")

  private fun localBook(): Book = book(id = "content://books/local")

  private fun book(id: String): Book {
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
        cover = null,
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
}
