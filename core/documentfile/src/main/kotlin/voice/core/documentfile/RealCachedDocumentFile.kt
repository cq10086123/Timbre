package voice.core.documentfile

import android.content.Context
import android.net.Uri

internal data class RealCachedDocumentFile(
  val context: Context,
  override val uri: Uri,
  private val preFilledContent: FileContents?,
) : CachedDocumentFile {

  // a single check memo: the value is queried once and reused; a benign race
  // under concurrency only queries twice and still ends up with the same
  // immutable contents
  @Volatile
  private var content: FileContents? = null

  @Volatile
  private var contentResolved = false

  private suspend fun content(): FileContents? {
    if (!contentResolved) {
      content = preFilledContent ?: FileContents.query(context, uri)
      contentResolved = true
    }
    return content
  }

  override suspend fun children(): List<CachedDocumentFile> {
    return if (isDirectory()) {
      parseContents(uri, context)
    } else {
      emptyList()
    }
  }

  override suspend fun name(): String? = content()?.name
  override suspend fun isDirectory(): Boolean = content()?.isDirectory ?: false
  override suspend fun isFile(): Boolean = content()?.isFile ?: false
  override suspend fun length(): Long = content()?.length ?: 0L
  override suspend fun lastModified(): Long = content()?.lastModified ?: 0L

  override fun toString(): String = "RealCachedDocumentFile($uri)"
}
