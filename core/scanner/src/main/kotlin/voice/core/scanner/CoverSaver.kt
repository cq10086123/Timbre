package voice.core.scanner

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import androidx.core.graphics.scale
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import voice.core.data.BookId
import voice.core.data.repo.BookRepository
import voice.core.logging.api.Logger
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import kotlin.math.max
import kotlin.uuid.Uuid

/**
 * Marker file for [bookId] inside [parent]. Shared by the stores that
 * remember per-book cover facts (user-chosen covers, applied remote folder
 * pictures).
 */
internal fun coverMarkerFile(
  parent: File,
  bookId: BookId,
): File {
  val digest = MessageDigest.getInstance("SHA-1")
    .digest(bookId.value.toByteArray())
    .joinToString("") { "%02x".format(it) }
  return File(parent, digest)
}

@Inject
public class CoverSaver
internal constructor(
  private val repo: BookRepository,
  private val context: Context,
) {

  // remembers the books whose cover was chosen manually (edit cover, cover
  // from the internet), so the scan never replaces them with an automatically
  // found one
  private val userCoverMarkerDir: File by lazy {
    File(context.filesDir, "bookCoversUserChosen").also { it.mkdirs() }
  }

  public suspend fun save(
    bookId: BookId,
    cover: Bitmap,
    fromUser: Boolean = false,
  ) {
    val newCover = newBookCoverFile()

    withContext(Dispatchers.IO) {
      // scale down if bitmap is too large
      val preferredSize = 1920
      val bitmapToSave = if (max(cover.width, cover.height) > preferredSize) {
        cover.scale(preferredSize, preferredSize)
      } else {
        cover
      }

      try {
        FileOutputStream(newCover).use {
          val compressFormat = when (newCover.extension) {
            "png" -> Bitmap.CompressFormat.PNG
            "webp" -> if (Build.VERSION.SDK_INT >= 30) {
              Bitmap.CompressFormat.WEBP_LOSSLESS
            } else {
              @Suppress("DEPRECATION")
              Bitmap.CompressFormat.WEBP
            }
            else -> error("Unhandled image extension for $newCover")
          }
          bitmapToSave.compress(compressFormat, 70, it)
          it.flush()
        }
      } catch (e: IOException) {
        Logger.w(e, "Error at saving image with destination=$newCover")
      }
    }

    setBookCover(newCover, bookId)
    if (fromUser) {
      runCatching { coverMarkerFile(userCoverMarkerDir, bookId).createNewFile() }
        .onFailure { Logger.w(it, "Could not write the user cover marker for $bookId") }
    }
  }

  /** Whether [bookId]'s current cover was chosen manually (never auto-replaced). */
  public fun hasUserChosenCover(bookId: BookId): Boolean {
    return coverMarkerFile(userCoverMarkerDir, bookId).exists()
  }

  internal suspend fun newBookCoverFile(): File {
    val coversFolder = withContext(Dispatchers.IO) {
      File(context.filesDir, "bookCovers")
        .also { coverFolder -> coverFolder.mkdirs() }
    }
    return File(coversFolder, "${Uuid.random()}.png")
  }

  internal suspend fun setBookCover(
    cover: File,
    bookId: BookId,
  ) {
    val oldCover = repo.get(bookId)?.content?.cover
    if (oldCover != null) {
      withContext(Dispatchers.IO) {
        oldCover.delete()
      }
    }

    repo.updateBook(bookId) {
      it.copy(cover = cover)
    }
  }
}
