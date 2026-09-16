package voice.core.scanner.mp4.visitor

import androidx.media3.common.util.ParsableByteArray
import voice.core.scanner.mp4.Mp4ChpaterExtractorOutput

internal interface AtomVisitor {
  val path: List<String>

  /**
   * Further paths this visitor is registered for. Some boxes can appear in more
   * than one place, for example the user data both inside `moov` and on the top
   * level of the file.
   */
  val additionalPaths: List<List<String>>
    get() = emptyList()

  val allPaths: List<List<String>>
    get() = listOf(path) + additionalPaths

  fun visit(
    buffer: ParsableByteArray,
    parseOutput: Mp4ChpaterExtractorOutput,
  )
}
