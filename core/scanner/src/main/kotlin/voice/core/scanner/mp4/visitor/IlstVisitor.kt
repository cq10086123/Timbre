package voice.core.scanner.mp4.visitor

import androidx.media3.common.util.ParsableByteArray
import dev.zacsweers.metro.Inject
import voice.core.logging.api.Logger
import voice.core.scanner.mp4.Mp4ChpaterExtractorOutput

/**
 * Reads the iTunes style tags of an `ilst` box.
 *
 * Only the tags the app shows are decoded, everything else is skipped without
 * looking at its content. That matters because the cover art alone can be
 * hundreds of kilobytes per file, and reading it for every chapter is what made
 * analyzing a book slow.
 */
@Inject
internal class IlstVisitor : AtomVisitor {

  override val path: List<String> = listOf("moov", "udta", "meta", "ilst")

  override val additionalPaths: List<List<String>> = listOf(
    listOf("udta", "meta", "ilst"),
  )

  override fun visit(
    buffer: ParsableByteArray,
    parseOutput: Mp4ChpaterExtractorOutput,
  ) {
    parseOutput.sawTags = true
    val end = buffer.position + buffer.bytesLeft()
    while (buffer.position + BOX_HEADER_SIZE <= end) {
      val itemSize = buffer.readUnsignedInt()
      val itemType = buffer.readUnsignedInt()
      val itemStart = buffer.position - BOX_HEADER_SIZE
      if (itemSize < BOX_HEADER_SIZE || itemSize > end - itemStart) {
        Logger.w("Unexpected size $itemSize for the ilst item $itemType")
        return
      }
      val itemEnd = itemStart + itemSize.toInt()
      // the atom types contain bytes that are not valid utf8 ('©nam' starts
      // with 0xA9), so they are compared as the numbers they are
      when (itemType) {
        TYPE_TITLE -> readText(buffer, itemEnd, parseOutput)?.let { parseOutput.title = it }
        TYPE_ARTIST -> readText(buffer, itemEnd, parseOutput)?.let { parseOutput.artist = it }
        TYPE_ALBUM -> readText(buffer, itemEnd, parseOutput)?.let { parseOutput.album = it }
        TYPE_GENRE -> readText(buffer, itemEnd, parseOutput)?.let { parseOutput.genre = it }
        TYPE_COMPOSER -> readText(buffer, itemEnd, parseOutput)?.let { parseOutput.narrator = it }
        TYPE_ALBUM_ARTIST -> {
          if (parseOutput.artist == null) {
            readText(buffer, itemEnd, parseOutput)?.let { parseOutput.artist = it }
          }
        }
        TYPE_FREEFORM -> {
          // freeform atoms carry their key in the atom itself, so resolving them
          // is left to the metadata retriever
          parseOutput.tagsNeedRetriever = true
        }
      }
      buffer.setPosition(itemEnd)
    }
  }

  /** Reads the text of the `data` child of an ilst item. */
  private fun readText(
    buffer: ParsableByteArray,
    itemEnd: Int,
    parseOutput: Mp4ChpaterExtractorOutput,
  ): String? {
    if (buffer.position + DATA_HEADER_SIZE > itemEnd) return null
    val dataStart = buffer.position
    val dataSize = buffer.readUnsignedInt()
    val dataType = buffer.readUnsignedInt()
    if (dataType != TYPE_DATA || dataSize < DATA_HEADER_SIZE) return null
    val dataEnd = minOf(dataStart + dataSize.toInt(), itemEnd)
    val typeFlags = buffer.readUnsignedInt()
    buffer.skipBytes(RESERVED_SIZE)
    if (typeFlags and TYPE_FLAGS_MASK != TEXT_TYPE_UTF8) {
      Logger.w("Unexpected data type ${typeFlags and TYPE_FLAGS_MASK} in an ilst item")
      parseOutput.tagsNeedRetriever = true
      return null
    }
    val textLength = dataEnd - buffer.position
    if (textLength <= 0) return null
    return buffer.readString(textLength).takeUnless { it.isBlank() }
  }
}

private const val BOX_HEADER_SIZE = 8

/** size and type of the box, then 4 bytes of type flags and 4 reserved bytes */
private const val DATA_HEADER_SIZE = 16L
private const val RESERVED_SIZE = 4
private const val TYPE_FLAGS_MASK = 0xFFFFFFL
private const val TEXT_TYPE_UTF8 = 1L

private const val TYPE_DATA = 0x64617461L // data
private const val TYPE_TITLE = 0xA96E616DL // ©nam
private const val TYPE_ARTIST = 0xA9415254L // ©ART
private const val TYPE_ALBUM = 0xA9616C62L // ©alb
private const val TYPE_GENRE = 0xA967656EL // ©gen
private const val TYPE_COMPOSER = 0xA9777274L // ©wrt
private const val TYPE_ALBUM_ARTIST = 0x61415254L // aART
private const val TYPE_FREEFORM = 0x2D2D2D2DL // ----
