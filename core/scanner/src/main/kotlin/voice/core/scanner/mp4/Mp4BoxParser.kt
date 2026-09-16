package voice.core.scanner.mp4

import androidx.media3.common.util.ParsableByteArray
import androidx.media3.container.Mp4Box
import dev.zacsweers.metro.Inject
import voice.core.logging.api.Logger
import voice.core.scanner.mp4.visitor.ChapVisitor
import voice.core.scanner.mp4.visitor.ChplVisitor
import voice.core.scanner.mp4.visitor.HdlrVisitor
import voice.core.scanner.mp4.visitor.IlstVisitor
import voice.core.scanner.mp4.visitor.KeysVisitor
import voice.core.scanner.mp4.visitor.MdhdVisitor
import voice.core.scanner.mp4.visitor.StcoVisitor
import voice.core.scanner.mp4.visitor.StscVisitor
import voice.core.scanner.mp4.visitor.SttsVisitor

@Inject
internal class Mp4BoxParser(
  stscVisitor: StscVisitor,
  mdhdVisitor: MdhdVisitor,
  sttsVisitor: SttsVisitor,
  stcoVisitor: StcoVisitor,
  chplVisitor: ChplVisitor,
  chapVisitor: ChapVisitor,
  hdlrVisitor: HdlrVisitor,
  ilstVisitor: IlstVisitor,
  keysVisitor: KeysVisitor,
) {

  private val visitors = listOf(
    stscVisitor,
    mdhdVisitor,
    sttsVisitor,
    stcoVisitor,
    chplVisitor,
    chapVisitor,
    hdlrVisitor,
    ilstVisitor,
    keysVisitor,
  )
  private val visitorByPath = visitors
    .flatMap { visitor -> visitor.allPaths.map { path -> path to visitor } }
    .toMap()
  private val paths = visitorByPath.keys.toList()

  operator fun invoke(input: Mp4BoxInput): Mp4ChpaterExtractorOutput {
    val scratch = ParsableByteArray(Mp4Box.LONG_HEADER_SIZE)
    val parseOutput = Mp4ChpaterExtractorOutput()
    parseBoxes(
      input = input,
      path = emptyList(),
      parentEnd = Long.MAX_VALUE,
      scratch = scratch,
      parseOutput = parseOutput,
    )
    return parseOutput
  }

  private fun parseBoxes(
    input: Mp4BoxInput,
    path: List<String>,
    parentEnd: Long,
    scratch: ParsableByteArray,
    parseOutput: Mp4ChpaterExtractorOutput,
  ) {
    while (input.position < parentEnd) {
      scratch.reset(Mp4Box.HEADER_SIZE)
      if (!input.readFully(scratch.data, 0, Mp4Box.HEADER_SIZE)) {
        return
      }

      var atomSize = scratch.readUnsignedInt()
      val atomType = scratch.readString(4)
      var headerSize = Mp4Box.HEADER_SIZE.toLong()

      if (atomSize == 1L) {
        if (!input.readFully(
            scratch.data,
            Mp4Box.HEADER_SIZE,
            Mp4Box.LONG_HEADER_SIZE - Mp4Box.HEADER_SIZE,
          )
        ) {
          return
        }
        scratch.setPosition(Mp4Box.HEADER_SIZE)
        atomSize = scratch.readUnsignedLongToLong()
        headerSize = Mp4Box.LONG_HEADER_SIZE.toLong()
      } else if (atomSize == 0L) {
        // the box reaches the end of the file, so there is nothing left to parse
        return
      }

      val payloadSize = atomSize - headerSize
      if (payloadSize < 0) {
        Logger.w("Invalid box size $atomSize for $atomType")
        return
      }
      val payloadEnd = input.position + payloadSize
      val currentPath = path + atomType
      Logger.d("Current path: $currentPath, atomType: $atomType")

      val visitor = visitorByPath[currentPath]

      when {
        visitor != null -> {
          Logger.v("Found ${visitor.path.last()}!")
          if (payloadSize > Int.MAX_VALUE) {
            Logger.w("Box $currentPath is too big to be parsed")
            return
          }
          val payload = payloadSize.toInt()
          scratch.reset(payload)
          if (!input.readFully(scratch.data, 0, payload)) {
            return
          }
          visitor.visit(scratch, parseOutput)

          if (parseOutput.isComplete()) {
            return
          }
        }
        paths.any { it.startsWith(currentPath) } -> {
          if (atomType == FULL_BOX && !input.skipFully(FULL_BOX_HEADER_SIZE)) {
            return
          }
          parseBoxes(
            input = input,
            path = currentPath,
            parentEnd = payloadEnd,
            scratch = scratch,
            parseOutput = parseOutput,
          )

          if (parseOutput.isComplete()) {
            return
          }
        }
        else -> {
          if (!input.skipFully(payloadSize)) {
            return
          }
        }
      }

      if (input.position < payloadEnd) {
        if (!input.skipFully(payloadEnd - input.position)) {
          return
        }
      }
    }
  }

  /**
   * True when there is nothing left to find. The tags are part of the condition
   * because a file can list its chapters before them, and stopping there would
   * lose the tags.
   */
  private fun Mp4ChpaterExtractorOutput.isComplete(): Boolean {
    return chplChapters.isNotEmpty() && sawTags
  }

  private fun List<String>.startsWith(other: List<String>): Boolean {
    return take(other.size) == other
  }
}

/** `meta` is a full box, so its children start after a version and flags field. */
private const val FULL_BOX = "meta"
private const val FULL_BOX_HEADER_SIZE = 4L
