package voice.core.online

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import voice.core.source.SourceRegistry

/**
 * Bridges jdr plugin sources into the [OnlineSourceBackend] contract. Source
 * names use the `jdr:{packageId}:{sourceId}` route form; books of these
 * sources live in the same shelf store as server books, so everything past
 * the search (shelf, position, bookmarks, cache) works unchanged.
 */
@Inject
@SingleIn(AppScope::class)
public class JdrOnlineSourceBackend(
  private val registry: SourceRegistry,
) : OnlineSourceBackend {

  override suspend fun sources(): List<OnlineSourceInfo> =
    registry.current().map {
      OnlineSourceInfo(
        name = "$JDR_PREFIX${it.id}",
        displayName = it.displayName,
        enabled = true,
      )
    }

  override suspend fun search(source: String, keyword: String): List<OnlineSearchResult> {
    val src = resolve(source)
    return src.search(keyword, 1).items.map { book ->
      OnlineSearchResult(
        source = source,
        bookId = book.id,
        title = book.title,
        author = book.author,
        cover = book.cover,
        intro = book.intro,
      )
    }
  }

  override suspend fun chapters(source: String, bookId: String): List<OnlineChapter> {
    val src = resolve(source)
    return src.chapters(bookId).mapIndexed { index, chapter ->
      OnlineChapter(
        id = chapter.id,
        title = chapter.title,
        durationSeconds = chapter.durationSeconds,
        order = chapter.order.takeIf { it > 0 } ?: (index + 1),
        extra = chapter.extra,
      )
    }
  }

  override suspend fun resolveAudio(
    source: String,
    bookId: String,
    chapterId: String,
    chapterExtra: String,
  ): OnlineAudio {
    val audio = resolve(source).audio(bookId, chapterId, chapterExtra)
    return OnlineAudio(url = audio.url, headers = audio.headers, expiresAt = audio.expiresAt)
  }

  /** True when at least one jdr source is installed and enabled. */
  public fun hasEnabledSources(): Boolean = registry.current().isNotEmpty()

  private fun resolve(source: String): voice.core.source.BookSource {
    val id = source.removePrefix(JDR_PREFIX)
    return registry.get(id)
      ?: throw IllegalStateException("Unknown or disabled jdr source: $source")
  }

  public companion object {
    public const val JDR_PREFIX: String = "jdr:"
  }
}
