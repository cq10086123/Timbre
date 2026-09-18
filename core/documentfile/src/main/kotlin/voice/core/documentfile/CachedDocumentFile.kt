package voice.core.documentfile

import android.net.Uri

interface CachedDocumentFile {
  val children: List<CachedDocumentFile>
  val name: String?
  val isDirectory: Boolean
  val isFile: Boolean
  val length: Long
  val lastModified: Long
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

fun CachedDocumentFile.nameWithoutExtension(): String {
  val name = name
  return if (name == null) {
    uri.pathSegments.lastOrNull()
      ?.dropWhile { it != ':' }
      ?.removePrefix(":")
      ?.takeUnless { it.isBlank() }
      ?: uri.toString()
  } else {
    if (isFile) {
      name.substringBeforeLast(".")
        .takeUnless { it.isEmpty() }
        ?: name
    } else {
      name
    }
  }
}
