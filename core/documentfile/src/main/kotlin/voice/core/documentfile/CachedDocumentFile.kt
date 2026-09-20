package voice.core.documentfile

import android.net.Uri

/**
 * A file or folder, local or remote.
 *
 * For remote implementations (e.g. WebDAV) the suspend members perform
 * network requests. Suspending instead of blocking makes it impossible to
 * freeze the main thread: the compiler only allows calls from coroutines,
 * where the caller controls the threading.
 */
interface CachedDocumentFile {
  suspend fun children(): List<CachedDocumentFile>
  suspend fun name(): String?
  suspend fun isDirectory(): Boolean
  suspend fun isFile(): Boolean
  suspend fun length(): Long
  suspend fun lastModified(): Long
  val uri: Uri

  /**
   * Non-null when the last attempt to read this file failed, for example
   * because the remote server it lives on is unreachable.
   *
   * An unreadable file and an empty one both have no [children], so the scan
   * cannot tell them apart on its own. Telling them apart matters, though: an
   * unreadable book has to stay on the shelf (with an error the user can
   * retry), while an empty folder was deleted and is removed from it.
   */
  val error: Throwable? get() = null
}

suspend fun CachedDocumentFile.nameWithoutExtension(): String {
  val name = name()
  return if (name == null) {
    uri.pathSegments.lastOrNull()
      ?.dropWhile { it != ':' }
      ?.removePrefix(":")
      ?.takeUnless { it.isBlank() }
      ?: uri.toString()
  } else {
    if (isFile()) {
      name.substringBeforeLast(".")
        .takeUnless { it.isEmpty() }
        ?: name
    } else {
      name
    }
  }
}
