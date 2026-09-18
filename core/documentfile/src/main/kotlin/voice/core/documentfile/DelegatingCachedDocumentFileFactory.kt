package voice.core.documentfile

import android.content.Context
import android.net.Uri
import androidx.core.net.toFile
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject

/**
 * Dispatches uri creation to the registered [DocumentFileSchemeHandler]s and
 * falls back to the documents provider / plain file implementations.
 */
@ContributesBinding(AppScope::class)
@Inject
class DelegatingCachedDocumentFileFactory(
  private val context: Context,
  private val handlers: Set<@JvmSuppressWildcards DocumentFileSchemeHandler>,
) : CachedDocumentFileFactory {

  override fun create(uri: Uri): CachedDocumentFile {
    return handlers.firstOrNull { it.supports(uri) }?.create(uri)
      ?: when (uri.scheme) {
        "file" -> FileBasedDocumentFile(uri.toFile())
        else -> RealCachedDocumentFile(context = context, uri = uri, preFilledContent = null)
      }
  }
}
