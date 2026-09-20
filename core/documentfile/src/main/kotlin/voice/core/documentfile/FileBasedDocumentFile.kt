package voice.core.documentfile

import android.net.Uri
import androidx.core.net.toFile
import androidx.core.net.toUri
import java.io.File

data class FileBasedDocumentFile(private val file: File) : CachedDocumentFile {

  override suspend fun children(): List<CachedDocumentFile> = file.listFiles()?.map { FileBasedDocumentFile(it) } ?: emptyList()
  override suspend fun name(): String? = file.name
  override suspend fun isDirectory(): Boolean = file.isDirectory
  override suspend fun isFile(): Boolean = file.isFile
  override suspend fun length(): Long = file.length()
  override suspend fun lastModified(): Long = file.lastModified()
  override val uri: Uri get() = file.toUri()
}

object FileBasedDocumentFactory : CachedDocumentFileFactory {
  override fun create(uri: Uri): CachedDocumentFile {
    return FileBasedDocumentFile(uri.toFile())
  }
}
