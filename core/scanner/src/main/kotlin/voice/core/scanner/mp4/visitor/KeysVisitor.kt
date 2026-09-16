package voice.core.scanner.mp4.visitor

import androidx.media3.common.util.ParsableByteArray
import dev.zacsweers.metro.Inject
import voice.core.logging.api.Logger
import voice.core.scanner.mp4.Mp4ChpaterExtractorOutput

/**
 * Detects the mdta form of the tags, where the tag names live in a `keys` box
 * and the items of the `ilst` box refer to them by index.
 *
 * Resolving those needs the metadata retriever, so a file that uses this form
 * cannot be analyzed from the boxes alone.
 */
@Inject
internal class KeysVisitor : AtomVisitor {

  override val path: List<String> = listOf("moov", "udta", "meta", "keys")

  override val additionalPaths: List<List<String>> = listOf(
    listOf("udta", "meta", "keys"),
  )

  override fun visit(
    buffer: ParsableByteArray,
    parseOutput: Mp4ChpaterExtractorOutput,
  ) {
    Logger.v("Found mdta keys with ${buffer.bytesLeft()} bytes")
    parseOutput.sawTags = true
    parseOutput.tagsNeedRetriever = true
  }
}
