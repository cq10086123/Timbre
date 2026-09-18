package voice.core.documentfile

import android.net.Uri

/**
 * Creates [CachedDocumentFile]s for the uri schemes it supports. Implementations
 * are collected by [DelegatingCachedDocumentFileFactory], which asks them in
 * turn before falling back to the documents provider implementation.
 */
interface DocumentFileSchemeHandler {

  fun supports(uri: Uri): Boolean

  fun create(uri: Uri): CachedDocumentFile
}
