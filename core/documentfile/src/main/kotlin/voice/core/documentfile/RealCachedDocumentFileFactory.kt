package voice.core.documentfile

import android.content.Context
import android.net.Uri
import dev.zacsweers.metro.Inject

@Inject
class RealCachedDocumentFileFactory(private val context: Context) : DocumentFileSchemeHandler {
  override fun supports(uri: Uri): Boolean {
    // the fallback in DelegatingCachedDocumentFileFactory covers content uris,
    // so this handler only claims them to make the fallback explicit
    return uri.scheme == "content"
  }

  override fun create(uri: Uri): CachedDocumentFile {
    return RealCachedDocumentFile(context = context, uri = uri, preFilledContent = null)
  }
}
