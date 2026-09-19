package voice.core.scanner

import android.graphics.Bitmap
import android.net.Uri
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import voice.core.data.Book
import voice.core.data.BookContent
import voice.core.data.BookId
import voice.core.data.Chapter
import voice.core.data.ChapterId
import voice.core.data.MarkData
import voice.core.documentfile.CachedDocumentFile
import voice.core.documentfile.CachedDocumentFileFactory
import voice.core.webdav.WebDavDataSourceFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class RemoteCoverFinderTest {

  private val tmp = Files.createTempDirectory("remoteCoverFinder").toFile()
  private val bookUrl = "https://nas.local/books/one"
  private val files = mutableMapOf<String, FakeCachedDocumentFile>()
  private val downloads = mutableMapOf<String, ByteArray?>()

  private val coverSaver = mockk<CoverSaver> {
    coEvery { newBookCoverFile() } answers {
      File(tmp, "cover-${Uuid.random()}.png")
    }
    coEvery { setBookCover(any(), any()) } just Runs
  }

  private val finder = RemoteCoverFinder(
    fileFactory = FakeFactory(files),
    // WebDavDataSourceFactory is a final class with an internal constructor,
    // so the test mocks it and stubs the unmarked data source instead.
    dataSourceFactory = mockk {
      every { createUnmarkedDataSource() } returns FakeDataSource(downloads)
    },
    coverSaver = coverSaver,
  )

  @Test
  fun savesAPictureFromTheFolder() = runTest {
    givenFolderWithImages("cover.jpg")

    val result = finder.findAndSaveCover(remoteBook())

    assertEquals(expected = true, actual = result)
    val coverSlot = slot<File>()
    coVerify(exactly = 1) { coverSaver.setBookCover(capture(coverSlot), any()) }
    assertTrue(coverSlot.captured.exists())
    assertContentEquals(expected = imageBytes, actual = coverSlot.captured.readBytes())
  }

  @Test
  fun fallsBackToTheNextCandidateWhenTheFirstDownloadFails() = runTest {
    givenFolderWithImages("broken.jpg", "cover.jpg")
    downloads["$bookUrl/broken.jpg"] = null

    val result = finder.findAndSaveCover(remoteBook())

    assertEquals(expected = true, actual = result)
    val coverSlot = slot<File>()
    coVerify(exactly = 1) { coverSaver.setBookCover(capture(coverSlot), any()) }
    assertContentEquals(expected = imageBytes, actual = coverSlot.captured.readBytes())
  }

  @Test
  fun noPictureInTheFolderIsADefinitiveAnswer() = runTest {
    files[bookUrl] = FakeCachedDocumentFile(
      uri = bookUrl.toUri(),
      isDirectory = true,
      children = listOf(fakeFile("$bookUrl/1.mp3", imageBytes)),
    )

    val result = finder.findAndSaveCover(remoteBook())

    assertEquals(expected = false, actual = result)
    coVerify(exactly = 0) { coverSaver.setBookCover(any(), any()) }
  }

  @Test
  fun aSingleFileHasNoFolderToSearch() = runTest {
    val id = "https://nas.local/books/one.mp3"
    files[id] = FakeCachedDocumentFile(uri = id.toUri(), isDirectory = false)

    val result = finder.findAndSaveCover(remoteBook(id))

    assertEquals(expected = false, actual = result)
  }

  @Test
  fun anUnreachableFolderIsInconclusive() = runTest {
    files[bookUrl] = FakeCachedDocumentFile(
      uri = bookUrl.toUri(),
      isDirectory = true,
      error = IOException("server down"),
    )

    val result = finder.findAndSaveCover(remoteBook())

    assertNull(result)
  }

  @Test
  fun aFailedDownloadIsInconclusive() = runTest {
    givenFolderWithImages("cover.jpg")
    downloads["$bookUrl/cover.jpg"] = null

    val result = finder.findAndSaveCover(remoteBook())

    assertNull(result)
  }

  @Test
  fun garbageInsteadOfAnImageIsInconclusive() = runTest {
    files[bookUrl] = FakeCachedDocumentFile(
      uri = bookUrl.toUri(),
      isDirectory = true,
      children = listOf(fakeFile("$bookUrl/cover.jpg", "<html>not an image</html>".toByteArray())),
    )
    // robolectric's bitmap shadow decodes any existing file, so the error
    // page is simulated with an empty response body instead: download()
    // then fails on the size check and the answer stays inconclusive
    downloads["$bookUrl/cover.jpg"] = ByteArray(0)

    val result = finder.findAndSaveCover(remoteBook())

    assertNull(result)
  }

  private fun givenFolderWithImages(vararg imageNames: String) {
    files[bookUrl] = FakeCachedDocumentFile(
      uri = bookUrl.toUri(),
      isDirectory = true,
      children = imageNames.map { name ->
        fakeFile("$bookUrl/$name", imageBytes)
      },
    )
  }

  private fun fakeFile(
    url: String,
    bytes: ByteArray,
  ): CachedDocumentFile {
    // the finder downloads through the data source, so the fake
    // data source has to serve the same bytes as the document file
    downloads[url] = bytes
    return FakeCachedDocumentFile(
      uri = url.toUri(),
      isDirectory = false,
      name = url.substringAfterLast('/'),
      childrenBytes = bytes,
    )
  }

  // a real png so the decode check of the finder accepts it
  private val imageBytes: ByteArray by lazy {
    val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
    val output = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
    output.toByteArray()
  }

  private fun remoteBook(id: String = bookUrl): Book {
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
        name = "RemoteBook",
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

  private class FakeFactory(private val files: Map<String, FakeCachedDocumentFile>) : CachedDocumentFileFactory {

    override fun create(uri: Uri): CachedDocumentFile {
      return files.getValue(uri.toString())
    }
  }

  private class FakeDataSource(private val downloads: Map<String, ByteArray?>) : DataSource {

    private var data: ByteArray? = null
    private var position = 0
    private var openedUri: Uri? = null

    override fun addTransferListener(transferListener: TransferListener) {}

    override fun open(dataSpec: DataSpec): Long {
      val bytes = downloads[dataSpec.uri.toString()] ?: throw IOException("Could not open ${dataSpec.uri}")
      data = bytes
      position = 0
      openedUri = dataSpec.uri
      return bytes.size.toLong()
    }

    override fun getUri(): Uri? = openedUri

    override fun read(
      target: ByteArray,
      offset: Int,
      length: Int,
    ): Int {
      val bytes = data ?: throw IOException("Not opened")
      if (position >= bytes.size) return C.RESULT_END_OF_INPUT
      val count = minOf(length, bytes.size - position)
      bytes.copyInto(target, offset, position, position + count)
      position += count
      return count
    }

    override fun close() {
      data = null
    }
  }

  private class FakeCachedDocumentFile(
    override val uri: Uri,
    override val isDirectory: Boolean,
    override val name: String? = uri.lastPathSegment,
    override val children: List<CachedDocumentFile> = emptyList(),
    override val error: Throwable? = null,
    private val childrenBytes: ByteArray? = null,
  ) : CachedDocumentFile {

    override val isFile: Boolean get() = !isDirectory
    override val length: Long get() = childrenBytes?.size?.toLong() ?: 0L
    override val lastModified: Long get() = 0L
  }
}
