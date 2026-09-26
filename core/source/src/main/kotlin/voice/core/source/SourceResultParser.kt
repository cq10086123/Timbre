package voice.core.source

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/** Thrown when a plugin returns data that violates the field contract. */
public class SourceResultException(message: String) : Exception(message)

/**
 * Turns raw plugin return values (JSON strings produced by `JSON.stringify`
 * inside the runtime) into the typed [SourcePage] / [SourceChapter] /
 * [SourceAudio] models, being liberal in what it accepts:
 *
 * - `search` may return `{items, nextPage, hasMore}` or a bare array,
 * - `chapters` may return a bare array or `{items}`,
 * - `audio` may return a plain url string or `{url, headers, expiresAt}`,
 * - chapter `order` defaults to the array index when absent.
 */
public class SourceResultParser {

  private val json = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
  }

  public fun parsePage(raw: String): SourcePage {
    val element = parse(raw, "search")
    val itemsElement: JsonElement = when (element) {
      is JsonArray -> element
      is JsonObject -> element["items"] ?: element["books"] ?: JsonArray(emptyList())
      else -> throw SourceResultException("search must return an object or array")
    }
    val items = decodeList(itemsElement, SourceBook.serializer())
    var hasMore = false
    var nextPage = ""
    if (element is JsonObject) {
      hasMore = (element["hasMore"] as? JsonPrimitive)?.booleanOrNull ?: false
      nextPage = (element["nextPage"] as? JsonPrimitive)?.content ?: ""
    }
    return SourcePage(items = items, nextPage = nextPage, hasMore = hasMore)
  }

  public fun parseChapters(raw: String): List<SourceChapter> {
    val element = parse(raw, "chapters")
    val itemsElement: JsonElement = when (element) {
      is JsonArray -> element
      is JsonObject -> element["items"] ?: element["chapters"] ?: JsonArray(emptyList())
      else -> throw SourceResultException("chapters must return an object or array")
    }
    val chapters = decodeList(itemsElement, SourceChapter.serializer())
    return chapters.mapIndexed { index, chapter ->
      if (chapter.order > 0) {
        chapter
      } else {
        chapter.copy(order = index + 1)
      }
    }
  }

  public fun parseAudio(raw: String): SourceAudio {
    val element = parse(raw, "audio")
    return when (element) {
      is JsonPrimitive -> {
        if (!element.isString) throw SourceResultException("audio url must be a string")
        SourceAudio(url = element.content)
      }
      is JsonObject -> {
        val url = (element["url"] as? JsonPrimitive)?.content
          ?: throw SourceResultException("audio result misses url")
        val headers = LinkedHashMap<String, String>()
        (element["headers"] as? JsonObject)?.forEach { (k, v) ->
          if (v is JsonPrimitive) headers[k] = v.content
        }
        val expiresAt = (element["expiresAt"] as? JsonPrimitive)?.longOrNull ?: 0L
        SourceAudio(url = url, headers = headers, expiresAt = expiresAt)
      }
      else -> throw SourceResultException("audio must return a url string or {url, headers}")
    }
  }

  private fun parse(raw: String, what: String): JsonElement {
    if (raw.isBlank() || raw == "null") {
      throw SourceResultException("$what returned nothing")
    }
    return try {
      json.parseToJsonElement(raw)
    } catch (e: Exception) {
      throw SourceResultException("$what returned invalid json: ${e.message}")
    }
  }

  private fun <T> decodeList(element: JsonElement, serializer: KSerializer<T>): List<T> =
    try {
      json.decodeFromJsonElement(ListSerializer(serializer), element)
    } catch (e: Exception) {
      throw SourceResultException("invalid items: ${e.message}")
    }
}
