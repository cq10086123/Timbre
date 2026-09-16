package voice.core.scanner.mp4.visitor

import androidx.media3.common.util.ParsableByteArray
import dev.zacsweers.metro.Inject
import voice.core.scanner.mp4.Mp4ChpaterExtractorOutput

// https://developer.apple.com/documentation/quicktime-file-format/handler_reference_atom
@Inject
internal class HdlrVisitor : AtomVisitor {

  override val path: List<String> = listOf("moov", "trak", "mdia", "hdlr")

  override fun visit(
    buffer: ParsableByteArray,
    parseOutput: Mp4ChpaterExtractorOutput,
  ) {
    // version and flags, then the predefined field, then the handler type
    val handlerType = if (buffer.bytesLeft() >= HANDLER_TYPE_OFFSET + HANDLER_TYPE_SIZE) {
      buffer.skipBytes(HANDLER_TYPE_OFFSET)
      buffer.readString(HANDLER_TYPE_SIZE)
    } else {
      ""
    }
    // an entry is added for every track so the handlers stay aligned with the
    // durations the mdhd visitor collects
    parseOutput.trackHandlers += handlerType
  }
}

private const val HANDLER_TYPE_OFFSET = 8
private const val HANDLER_TYPE_SIZE = 4
