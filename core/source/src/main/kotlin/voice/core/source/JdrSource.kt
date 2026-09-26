package voice.core.source

import kotlinx.serialization.json.JsonPrimitive
import voice.core.source.runtime.SourceScriptException

/**
 * [BookSource] backed by a jdr bundle. All calls forward into the QuickJS
 * runtime; results are parsed and validated by [SourceResultParser].
 */
public class JdrSource internal constructor(
  private val pool: JdrRuntimePool,
  /** The package this source belongs to. */
  override val packageId: String,
  /** The source id as the plugin declared it. */
  public val sourceId: String,
  /** Display name from the plugin or the manifest. */
  override val displayName: String,
) : BookSource {

  private val parser = SourceResultParser()

  /** Unique key inside the app: `packageId:sourceId`. */
  override val id: String = "$packageId:$sourceId"

  override suspend fun search(keyword: String, page: Int): SourcePage {
    val raw = call("search", keyword, page.toString())
    return parser.parsePage(raw)
  }

  override suspend fun chapters(bookId: String): List<SourceChapter> {
    val raw = call("chapters", bookId)
    return parser.parseChapters(raw)
  }

  override suspend fun audio(
    bookId: String,
    chapterId: String,
    chapterExtra: String,
  ): SourceAudio {
    val raw = call("audio", bookId, chapterId, chapterExtra)
    return parser.parseAudio(raw)
  }

  private suspend fun call(method: String, vararg args: String): String {
    val encoded = args.map { JsonPrimitive(it).toString() }
    return try {
      pool.call(packageId, sourceId, method, encoded)
    } catch (e: SourceScriptException) {
      throw e
    } catch (e: Exception) {
      throw SourceScriptException("source $id $method failed: ${e.message}", e)
    }
  }

  override fun toString(): String = "JdrSource($id)"
}
