package voice.core.scanner

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import voice.core.common.PlaybackIoGate
import voice.core.data.Book
import voice.core.data.toUri
import voice.core.logging.api.Logger
import java.io.File
import java.io.IOException
import java.security.MessageDigest

@Inject
internal class CoverScanner(
  private val context: Context,
  private val coverSaver: CoverSaver,
  private val coverExtractor: CoverExtractor,
  private val playbackIoGate: PlaybackIoGate,
  @MediaAnalysisSemaphore private val semaphore: Semaphore,
) {

  // remembers books that have no cover anywhere, so we don't re-extract the
  // first chapters on every single scan
  private val markerDir: File by lazy {
    File(context.filesDir, "bookCoversNoArt").also { it.mkdirs() }
  }

  suspend fun scan(books: List<Book>) {
    coroutineScope {
      books
        .map { book ->
          async(Dispatchers.IO) {
            // cover lookup reads the audio files as well, so it waits for
            // playback before it takes an io slot
            playbackIoGate.withScannerIoSlot(semaphore) {
              try {
                findCoverForBook(book)
              } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
              } catch (e: Exception) {
                Logger.w(e, "Error while finding a cover for ${book.id}")
              }
            }
          }
        }
        .joinAll()
    }
  }

  private suspend fun findCoverForBook(book: Book) {
    val coverFile = book.content.cover
    if (coverFile != null && coverFile.exists()) {
      return
    }

    val marker = markerFile(book)
    if (marker.exists()) {
      return
    }

    val foundOnDisc = findAndSaveCoverFromDisc(book)
    if (foundOnDisc) {
      return
    }

    val foundEmbedded = scanForEmbeddedCover(book)
    if (!foundEmbedded) {
      runCatching { marker.createNewFile() }
        .onFailure { Logger.w(it, "Could not write no-cover marker for ${book.id}") }
    }
  }

  private fun markerFile(book: Book): File {
    val digestInput = buildString {
      append(book.id.value)
      book.chapters.forEach { chapter ->
        append(chapter.id.value)
        append('|')
        append(chapter.fileLastModified)
        append('|')
        append(chapter.fileSize)
        append(';')
      }
    }
    val digest = MessageDigest.getInstance("SHA-1")
      .digest(digestInput.toByteArray())
      .joinToString("") { "%02x".format(it) }
    return File(markerDir, digest)
  }

  private suspend fun findAndSaveCoverFromDisc(book: Book): Boolean = withContext(Dispatchers.IO) {
    val documentFile = try {
      DocumentFile.fromTreeUri(context, book.id.toUri())
    } catch (_: IllegalArgumentException) {
      null
    } ?: return@withContext false

    if (!documentFile.isDirectory) {
      return@withContext false
    }

    documentFile.listFiles().forEach { child ->
      if (child.isFile && child.canRead() && child.type?.startsWith("image/") == true) {
        val coverFile = coverSaver.newBookCoverFile()
        val worked = try {
          context.contentResolver.openInputStream(child.uri)?.use { input ->
            coverFile.outputStream().use { output ->
              input.copyTo(output)
            }
          }
          true
        } catch (e: IOException) {
          Logger.w(e, "Error while copying the cover from ${child.uri}")
          false
        } catch (e: IllegalStateException) {
          // On some Samsung Devices, openInputStream throws this exception, though it should not.
          Logger.w(e, "Error while copying the cover from ${child.uri}")
          false
        }
        if (worked) {
          coverSaver.setBookCover(coverFile, book.id)
          return@withContext true
        }
      }
    }

    false
  }

  private suspend fun scanForEmbeddedCover(book: Book): Boolean {
    val coverFile = coverSaver.newBookCoverFile()
    book.chapters
      .take(5).forEach { chapter ->
        val success = coverExtractor.extractCover(
          input = chapter.id.toUri(),
          outputFile = coverFile,
        )
        if (success && coverFile.exists() && coverFile.length() > 0) {
          coverSaver.setBookCover(coverFile, bookId = book.id)
          return true
        }
      }
    return false
  }
}
