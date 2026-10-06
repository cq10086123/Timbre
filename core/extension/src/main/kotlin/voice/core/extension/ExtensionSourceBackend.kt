package voice.core.extension

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import voice.core.extension.engine.SearchItem
import voice.core.extension.engine.SourceContract
import voice.core.online.ExtensionOnlineSource
import voice.core.online.OnlineChapter
import voice.core.online.OnlineSearchResult
import voice.core.online.OnlineSourceInfo
import voice.core.online.ResolvedStream
import java.util.LinkedHashMap

/** sourceId/bookId 级别的透传字段缓存；容量有限，防止长会话内存无限增长。 */
private class BoundedCache(private val maxSize: Int = 200) {

  private val map = object : LinkedHashMap<String, JsonObject>(16, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, JsonObject>): Boolean = size > maxSize
  }

  @Synchronized
  public fun get(key: String): JsonObject? = map[key]

  @Synchronized
  public fun put(
    key: String,
    value: JsonObject,
  ) {
    map[key] = value
  }
}

/**
 * Bridges the `jdr:` prefixed online sources to locally executed extension
 * scripts. Search/chapter extras (custom fields) are remembered in memory and
 * merged into the next stage's params, mirroring the reference contract.
 */
@Inject
@SingleIn(AppScope::class)
public class ExtensionSourceBackend(
  private val engineProvider: ExtensionEngineProvider,
  private val manager: ExtensionManager,
) : ExtensionOnlineSource {

  /** "<sourceId>/<bookId>" → search result extras. */
  private val searchExtras = BoundedCache()

  /** "<sourceId>/<bookId>/<chapterId>" → chapter extras. */
  private val chapterExtras = BoundedCache()

  override fun handles(source: String): Boolean = source.startsWith(ExtensionEngineProvider.SOURCE_PREFIX)

  override suspend fun hasEnabledSources(): Boolean = manager.installed().any { it.enabledSources().isNotEmpty() }

  override suspend fun enabledSourceInfos(): List<OnlineSourceInfo> {
    return manager.installed().flatMap { pkg ->
      pkg.enabledSources().map { meta ->
        OnlineSourceInfo(
          name = ExtensionEngineProvider.SOURCE_PREFIX + meta.id,
          displayName = meta.name.ifBlank { meta.id },
          enabled = true,
        )
      }
    }
  }

  override suspend fun search(
    source: String,
    keyword: String,
  ): List<OnlineSearchResult> {
    val sourceId = sourceId(source)
    val params = params(
      base = linkedMapOf("keyword" to keyword, "page" to 1, "limit" to 30),
    )
    val json = engineProvider.useEngine(sourceId) {
      // no stage-level cap: sources control timing themselves via per-request
      // timeoutMs options; OkHttp still bounds each request (connect 15s /
      // read 30s unless the script overrides)
      it.invoke("search", params)
    }
    val items = SourceContract.parseSearchResults(json)
    items.forEach { item ->
      searchExtras.put("$sourceId/${item.id}", item.extra)
    }
    return items.map { item ->
      OnlineSearchResult(
        source = source,
        bookId = item.id,
        title = item.title,
        author = item.author,
        cover = item.cover,
        intro = item.intro,
        trackCount = item.trackCount,
      )
    }
  }

  override suspend fun chapters(
    source: String,
    bookId: String,
  ): List<OnlineChapter> {
    val sourceId = sourceId(source)
    val params = params(
      base = linkedMapOf("bookId" to bookId, "page" to 1, "size" to 0),
      extras = searchExtras.get("$sourceId/$bookId"),
    )
    val json = engineProvider.useEngine(sourceId) {
      it.invoke("chapters", params)
    }
    val items = SourceContract.parseChapters(json)
    items.forEach { item ->
      chapterExtras.put("$sourceId/$bookId/${item.id}", item.extra)
    }
    return items.map { item ->
      OnlineChapter(
        id = item.id,
        title = item.title,
        durationSeconds = item.durationSeconds.toInt(),
        order = item.order,
      )
    }
  }

  override suspend fun resolveDirectUrl(
    source: String,
    bookId: String,
    chapterId: String,
  ): ResolvedStream? {
    val sourceId = sourceId(source)
    val params = params(
      base = linkedMapOf("bookId" to bookId, "chapterId" to chapterId),
      extras = chapterExtras.get("$sourceId/$bookId/$chapterId"),
    )
    val json = engineProvider.useEngine(sourceId) {
      it.invoke("audio", params)
    }
    val parsed = SourceContract.parseAudio(json)
    return ResolvedStream(parsed.url, parsed.headers)
  }

  private fun sourceId(source: String): String = source.removePrefix(ExtensionEngineProvider.SOURCE_PREFIX)

  private fun params(
    base: LinkedHashMap<String, Any>,
    extras: JsonObject? = null,
  ): String {
    val merged = HashMap<String, JsonPrimitive>()
    extras?.forEach { (name, value) -> (value as? JsonPrimitive)?.let { merged[name] = it } }
    base.forEach { (name, value) ->
      merged[name] = when (value) {
        is Number -> JsonPrimitive(value)
        else -> JsonPrimitive(value.toString())
      }
    }
    return JsonObject(merged).toString()
  }
}
