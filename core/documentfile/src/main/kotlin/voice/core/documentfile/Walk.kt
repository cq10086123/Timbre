package voice.core.documentfile

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Lazily walks this file and, for directories, its whole subtree in depth
 * first order. The file itself is always the first element.
 */
fun CachedDocumentFile.walk(): Flow<CachedDocumentFile> = flow {
  suspend fun kotlinx.coroutines.flow.FlowCollector<CachedDocumentFile>.walk(file: CachedDocumentFile) {
    emit(file)
    if (file.isDirectory()) {
      file.children().forEach { walk(it) }
    }
  }
  walk(this@walk)
}
