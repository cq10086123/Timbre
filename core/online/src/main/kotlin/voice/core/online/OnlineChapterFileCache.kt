package voice.core.online

import java.io.File
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * Local files of manually cached online chapters, so cached episodes keep
 * playing offline. Layout:
 * `online_chapters/<source>/<bookId>/<chapterId>.audio`, downloads land in a
 * `.tmp` sibling first and are renamed only when complete - a file with the
 * final name is therefore always a whole chapter.
 *
 * This is deliberately separate from every automatic cache: the main catalog
 * downloads ahead on the server (not on the device), resolved stream urls
 * live in memory with a short ttl, and the webdav prefetch only knows http(s)
 * urls - none of them ever reads or writes here, so manual caching cannot
 * fight automatic playback.
 *
 * Provided by [OnlineSourceGraph] with the app files dir; tests construct it
 * with a temp dir directly.
 */
public class OnlineChapterFileCache internal constructor(private val root: File) {

  /** The final (complete only) file of [ref]; absent when not cached. */
  public fun fileFor(ref: OnlineChapterRef): File {
    return File(File(File(root, safe(ref.source)), safe(ref.bookId)), safe(ref.chapterId) + EXTENSION)
  }

  /** True when a complete file of [ref] is stored. */
  public fun isCached(ref: OnlineChapterRef): Boolean {
    val file = fileFor(ref)
    return file.exists() && file.length() > 0L
  }

  /** How many complete chapters of a book are stored. */
  public fun cachedFileCount(
    source: String,
    bookId: String,
  ): Int {
    val dir = File(File(root, safe(source)), safe(bookId))
    return dir.listFiles()?.count { it.isFile && it.name.endsWith(EXTENSION) && it.length() > 0L } ?: 0
  }

  /** How many bytes of complete chapters of a book are stored. */
  public fun cachedBytes(
    source: String,
    bookId: String,
  ): Long {
    val dir = File(File(root, safe(source)), safe(bookId))
    return dir.listFiles()
      ?.filter { it.isFile && it.name.endsWith(EXTENSION) }
      ?.sumOf { it.length() } ?: 0L
  }

  /**
   * Drops every cached chapter of a book (including partial downloads).
   * Returns true when anything was removed.
   */
  public fun clearBook(
    source: String,
    bookId: String,
  ): Boolean {
    val dir = File(File(root, safe(source)), safe(bookId))
    if (!dir.exists()) return false
    return dir.deleteRecursively()
  }

  /** The scratch file a download of [ref] is written to. */
  internal fun tmpFileFor(ref: OnlineChapterRef): File {
    val final = fileFor(ref)
    return File(final.parent, final.name + TMP_SUFFIX)
  }

  /** Publishes a finished download of [ref]; false when there is nothing complete to publish. */
  internal fun completeDownload(ref: OnlineChapterRef): Boolean {
    val tmp = tmpFileFor(ref)
    if (!tmp.exists() || tmp.length() <= 0L) {
      tmp.delete()
      return false
    }
    val dest = fileFor(ref)
    if (dest.exists()) dest.delete()
    return tmp.renameTo(dest)
  }

  /** Drops a partial download of [ref], e.g. after a cancellation. */
  internal fun discardTmp(ref: OnlineChapterRef) {
    tmpFileFor(ref).delete()
  }

  private fun safe(segment: String): String {
    val encoded = URLEncoder.encode(segment, Charsets.UTF_8.name()).replace("+", "%20")
    if (encoded.length <= MAX_SEGMENT_LENGTH) return encoded
    // very long ids would overflow the file name limit: hash them instead
    return MessageDigest.getInstance("SHA-256")
      .digest(segment.toByteArray(Charsets.UTF_8))
      .joinToString("") { "%02x".format(it) }
  }

  internal companion object {
    const val CACHE_DIR = "online_chapters"
    const val EXTENSION = ".audio"
    const val TMP_SUFFIX = ".tmp"
    const val MAX_SEGMENT_LENGTH = 100
  }
}
