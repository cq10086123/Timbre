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
import voice.core.documentfile.CachedDocumentFile
import voice.core.documentfile.CachedDocumentFileFactory
import voice.core.logging.api.Logger
import voice.core.webdav.WebDavDataSourceFactory
import java.io.File
import kotlin.io.DEFAULT_BUFFER_SIZE

/**
 * The outcome of searching the remote folder of a book (e.g. WebDAV) for a
 * cover picture.
 */
internal sealed interface RemoteCoverLookup {

  /**
   * A picture of the folder is now the book cover. [appliedMarker] identifies
   * that picture, so the next scan can keep it without downloading again as
   * long as it stays unchanged.
   */
  data class Applied(val appliedMarker: String) : RemoteCoverLookup

  /**
   * The folder was readable and contains no usable picture. This is the
   * definitive "no", the embedded artwork may take over.
   */
  data object NoPictureInFolder : RemoteCoverLookup

  /**
   * The folder or the picture could not be read (unreachable server, failed
   * listing or download). The answer is not trustworthy, so the caller must
   * not remember it and ask again on the next scan.
   */
  data object Inconclusive : RemoteCoverLookup
}

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
   * [alreadyApplied] is the marker of the picture that is already the book
   * cover (see [RemoteCoverLookup.Applied.appliedMarker]). When the folder
   * still lists exactly that picture, the download is skipped.
   */
  suspend fun findAndSaveCover(
    book: Book,
    alreadyApplied: String? = null,
  ): RemoteCoverLookup = withContext(Dispatchers.IO) {
    val folder = fileFactory.create(book.id.toUri())
    // accessing isDirectory is the first read: only after it the error of the
    // folder is trustworthy. A check before this access would always see null
    // and turn an unreachable server into a definitive "no picture".
    val isDirectory = folder.isDirectory
    if (folder.error != null) return@withContext RemoteCoverLookup.Inconclusive
    // a single remote audio file has no folder to search
    if (!isDirectory) return@withContext RemoteCoverLookup.NoPictureInFolder

    val candidates = folder.children
    if (folder.error != null) {
      // a failed listing surfaces as an empty folder; treating it as "no
      // picture" would pin the book to its embedded artwork because of one
      // dropped connection
      return@withContext RemoteCoverLookup.Inconclusive
    }

    val pictures = candidates
      .filter { it.isFile && it.name?.isSupportedImageFileName() == true }
      // a deterministic order instead of a random pick: the folder is listed
      // on every scan, and a new random winner each time would make the cover
      // flicker between scans. Conventional cover names win, the rest counts
      // in alphabetically.
      .sortedWith(
        compareByDescending<CachedDocumentFile> { it.name.orEmpty().coverNamePriority() }
          .thenComparator { left, right ->
            left.name.orEmpty().lowercase().compareTo(right.name.orEmpty().lowercase())
          },
      )
    if (pictures.isEmpty()) return@withContext RemoteCoverLookup.NoPictureInFolder

    var failed = false
    for (candidate in pictures) {
      if (alreadyApplied != null && candidate.matchesMarker(alreadyApplied)) {
        // the folder still offers the picture that is already the cover
        return@withContext RemoteCoverLookup.Applied(alreadyApplied)
      }
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
        return@withContext RemoteCoverLookup.Applied(candidate.marker())
      }
      failed = true
      coverFile.delete()
    }
    // the folder itself was readable, so a failed download is a transient
    // problem: the book is worth another look on the next scan
    RemoteCoverLookup.Inconclusive
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

  private fun CachedDocumentFile.marker(): String {
    return "${uri}|$length|$lastModified"
  }

  private fun CachedDocumentFile.matchesMarker(marker: String): Boolean {
    val parts = marker.split('|')
    if (parts.size != 3) return false
    return uri.toString() == parts[0] &&
      length.toString() == parts[1] &&
      lastModified.toString() == parts[2]
  }

  private fun String.coverNamePriority(): Int {
    val stem = substringBeforeLast('.').lowercase()
    return if (stem in conventionalCoverNames) 0 else 1
  }

  private companion object {
    // file names that conventionally mark the cover picture of a folder
    val conventionalCoverNames = setOf("cover", "folder", "front", "poster", "album", "artwork")
  }
}
