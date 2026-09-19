package voice.core.scanner

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import voice.core.data.Book
import voice.core.data.toUri
import voice.core.documentfile.CachedDocumentFileFactory
import voice.core.logging.api.Logger
import voice.core.webdav.WebDavDataSourceFactory
import java.io.File
import java.io.IOException
import kotlin.io.DEFAULT_BUFFER_SIZE

/**
 * Finds a cover image inside the remote folder of a book, e.g. a WebDAV folder
 * on a NAS, and stores it as the book cover - the same preference the local
 * scan applies to pictures lying next to the audio files.
 */
@Inject
internal class RemoteCoverFinder(
  private val fileFactory: CachedDocumentFileFactory,
  private val dataSourceFactory: WebDavDataSourceFactory,
  private val coverSaver: CoverSaver,
) {

  /**
   * Returns true when a picture was found and saved as the cover, false when
   * the folder was readable and has no usable picture, and null when the
   * result is unknown (unreachable server, failed download). Only a
   * definitive answer may stop the caller from looking again.
   */
  suspend fun findAndSaveCover(book: Book): Boolean? = withContext(Dispatchers.IO) {
    val folder = fileFactory.create(book.id.toUri())
    if (folder.error != null) return@withContext null
    // a single remote audio file has no folder to search
    if (!folder.isDirectory) return@withContext false

    val candidates = folder.children
      .filter { it.isFile && it.name?.isSupportedImageFileName() == true }
      .shuffled()
    if (candidates.isEmpty()) return@withContext false

    var failed = false
    for (candidate in candidates) {
      val coverFile = coverSaver.newBookCoverFile()
      val usable = try {
        download(candidate.uri, coverFile) && coverFile.isDecodableImage()
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Logger.w(e, "Could not download the cover from ${candidate.uri}")
        false
      }
      if (usable) {
        coverSaver.setBookCover(coverFile, book.id)
        return@withContext true
      }
      failed = true
      coverFile.delete()
    }
    // the folder itself was readable, so a failed download is a transient
    // problem: the book is worth another look on the next scan
    if (failed) null else false
  }

  private fun download(
    uri: Uri,
    outputFile: File,
  ): Boolean {
    // the unmarked variant keeps the cover download out of the playback cache
    // statistics; DataSource is not Closeable, so close it manually
    val dataSource = dataSourceFactory.createUnmarkedDataSource()
    try {
      dataSource.open(DataSpec(uri))
      outputFile.outputStream().use { output ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
          val read = dataSource.read(buffer, 0, buffer.size)
          if (read == C.RESULT_END_OF_INPUT) break
          output.write(buffer, 0, read)
        }
      }
    } finally {
      dataSource.close()
    }
    return outputFile.length() > 0
  }

  // a server answering an image request with an error page would otherwise
  // end up as a broken cover, so the bytes have to decode as an image
  private fun File.isDecodableImage(): Boolean {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(absolutePath, options)
    return options.outWidth > 0 && options.outHeight > 0
  }
}
