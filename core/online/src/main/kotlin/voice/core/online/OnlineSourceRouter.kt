package voice.core.online

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * Dispatches search, chapters and audio resolution to the right backend: the
 * built-in server source, or a jdr plugin source (name prefix `jdr:`). The
 * rest of the pipeline - shelf, position, bookmarks, cache - sees one
 * uniform interface.
 */
@Inject
@SingleIn(AppScope::class)
public class OnlineSourceRouter(
  private val server: OnlineSourceService,
  private val jdr: JdrOnlineSourceBackend,
) : OnlineSourceBackend {

  override suspend fun sources(): List<OnlineSourceInfo> = coroutineScope {
    // a misconfigured or unreachable server must not hide the plugin sources
    val serverSources = async { runCatching { server.sources() }.getOrDefault(emptyList()) }
    val jdrSources = async { jdr.sources() }
    serverSources.await() + jdrSources.await()
  }

  override suspend fun search(source: String, keyword: String): List<OnlineSearchResult> =
    backend(source).search(source, keyword)

  override suspend fun chapters(source: String, bookId: String): List<OnlineChapter> =
    backend(source).chapters(source, bookId)

  override suspend fun resolveAudio(
    source: String,
    bookId: String,
    chapterId: String,
    chapterExtra: String,
  ): OnlineAudio = backend(source).resolveAudio(source, bookId, chapterId, chapterExtra)

  /** True when anything is searchable: a configured server or a jdr source. */
  public suspend fun isAvailable(): Boolean =
    jdr.hasEnabledSources() || runCatching { server.isConfigured() }.getOrDefault(false)

  private fun backend(source: String): OnlineSourceBackend =
    if (source.startsWith(JdrOnlineSourceBackend.JDR_PREFIX)) jdr else server
}
