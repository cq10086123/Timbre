package voice.core.scanner.mp4

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import voice.core.logging.api.Logger
import java.io.Closeable
import java.io.IOException

/**
 * Sequential reader for the boxes of an mp4 style file.
 *
 * Skipping a box reopens the source at the target position instead of reading
 * through it. Reading through means copying every skipped byte, which for the
 * `mdat` box is the whole audio payload and makes analyzing a single chapter
 * take seconds.
 */
internal class Mp4BoxInput(
  private val uri: Uri,
  val dataSource: DataSource,
) : Closeable {

  var position: Long = 0
    private set

  private val skipBuffer = ByteArray(SKIP_BUFFER_SIZE)

  fun open() {
    dataSource.open(DataSpec(uri))
    position = 0
  }

  /** Reads exactly [length] bytes. Returns false when the end of the file is reached first. */
  fun readFully(
    target: ByteArray,
    offset: Int,
    length: Int,
  ): Boolean {
    var bytesRead = 0
    while (bytesRead < length) {
      val read = dataSource.read(target, offset + bytesRead, length - bytesRead)
      if (read == C.RESULT_END_OF_INPUT) {
        return false
      }
      bytesRead += read
      position += read
    }
    return true
  }

  /** Moves [length] bytes forward. Returns false when the end of the file is reached first. */
  fun skipFully(length: Long): Boolean {
    if (length <= 0) {
      return true
    }
    if (length >= SEEK_THRESHOLD && reopenAt(position + length)) {
      return true
    }
    var remaining = length
    while (remaining > 0) {
      val chunkSize = minOf(remaining, skipBuffer.size.toLong()).toInt()
      if (!readFully(skipBuffer, 0, chunkSize)) {
        return false
      }
      remaining -= chunkSize
    }
    return true
  }

  private fun reopenAt(target: Long): Boolean {
    return try {
      dataSource.close()
      dataSource.open(
        DataSpec.Builder()
          .setUri(uri)
          .setPosition(target)
          .build(),
      )
      position = target
      true
    } catch (e: IOException) {
      Logger.w(e, "Could not seek to $target in $uri")
      false
    }
  }

  override fun close() {
    try {
      dataSource.close()
    } catch (e: IOException) {
      Logger.w(e, "Error closing data source")
    }
  }

  companion object {
    private const val SKIP_BUFFER_SIZE = 64 * 1024

    /**
     * Reopening costs a round trip to the documents provider, so short skips
     * (the small boxes inside `moov`) are cheaper to read through.
     */
    private const val SEEK_THRESHOLD = 256 * 1024L

    fun create(
      uri: Uri,
      dataSource: DataSource,
    ): Mp4BoxInput {
      return Mp4BoxInput(uri, dataSource)
    }
  }
}
