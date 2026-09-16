package voice.core.scanner

import android.content.Context
import android.graphics.BitmapFactory
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
import java.util.Locale

@Inject
internal class CoverScanner(
  private val context: Context,
  private val coverSaver: CoverSaver,
  private val coverExtractor: CoverExtractor,
  private val coverGenerator: CoverGenerator,
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

    // a picture next to the audio files is the cheapest source, and the one
    // worth re-checking on every scan: dropping an image into the folder later
    // still replaces the drawn placeholder, marker or not
    val foundOnDisc = findAndSaveCoverFromDisc(book)
    if (foundOnDisc) {
      return
    }

    val marker = markerFile(book)
    if (marker.exists()) {
      // the audio files were searched for artwork before and contain none.
      // The book still shouldn't sit on the shelf without a cover, so one is
      // drawn from its name.
      generateCover(book)
      return
    }

    val foundEmbedded = scanForEmbeddedCover(book)
    if (!foundEmbedded) {
      // Neither on the disc nor embedded: draw one from the book name so the
      // shelf shows a cover instead of an empty placeholder. The marker keeps
      // the next scan from searching the files again.
      generateCover(book)
      runCatching { marker.createNewFile() }
        .onFailure { Logger.w(it, "Could not write no-cover marker for ${book.id}") }
    }
  }

  private suspend fun generateCover(book: Book) {
    val bookName = book.content.name
    if (bookName.isBlank()) {
      return
    }
    runCatching {
      coverSaver.save(book.id, coverGenerator.create(bookName))
    }.onSuccess {
      Logger.i("Generated a cover for ${book.id}")
    }.onFailure {
      Logger.w(it, "Could not generate a cover for ${book.id}")
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

    // several pictures in the folder are equally good, so one is drawn at
    // random. Candidates the platform cannot decode are skipped, so a file in
    // an exotic format doesn't end up as a cover just because it was drawn.
    documentFile
      .listFiles()
      .filter { it.isFile && it.canRead() && it.isSupportedImage() }
      .shuffled()
      .forEach { candidate ->
        if (!candidate.isDecodable()) return@forEach
        val coverFile = coverSaver.newBookCoverFile()
        val worked = try {
          context.contentResolver.openInputStream(candidate.uri)?.use { input ->
            coverFile.outputStream().use { output ->
              input.copyTo(output)
            }
          }
          true
        } catch (e: IOException) {
          Logger.w(e, "Error while copying the cover from ${candidate.uri}")
          false
        } catch (e: IllegalStateException) {
          // On some Samsung Devices, openInputStream throws this exception, though it should not.
          Logger.w(e, "Error while copying the cover from ${candidate.uri}")
          false
        }
        if (worked) {
          coverSaver.setBookCover(coverFile, book.id)
          return@withContext true
        }
      }

    false
  }

  // formats BitmapFactory can decode. Anything else would only end up as a
  // broken cover file, so it is treated as if no picture was there.
  private val imageExtensions = setOf(
    "avif",
    "bmp",
    "gif",
    "heic",
    "heif",
    "ico",
    "jfif",
    "jpe",
    "jpeg",
    "jpg",
    "png",
    "wbmp",
    "webp",
  )

  private fun DocumentFile.isSupportedImage(): Boolean {
    val type = type
    if (type != null) {
      // vector graphics cannot be decoded to a bitmap
      if (type.equals("image/svg+xml", ignoreCase = true)) return false
      if (type.startsWith("image/")) return true
    }
    // some providers report no mime type at all, the extension is all there is
    val extension = name?.substringAfterLast('.', missingDelimiterValue = "")?.lowercase(Locale.US)
    return extension in imageExtensions
  }

  private fun DocumentFile.isDecodable(): Boolean {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    val decoded = runCatching {
      context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    }
    return decoded.isSuccess && options.outWidth > 0 && options.outHeight > 0
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
