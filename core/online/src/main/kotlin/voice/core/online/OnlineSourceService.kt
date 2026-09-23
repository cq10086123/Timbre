package voice.core.online

import androidx.datastore.core.DataStore
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Facade over [OnlineSourceClient] that owns the configuration stores and the
 * token lifecycle: the token is cached, and on expiry the card key login is
 * repeated silently (the captcha is solvable programmatically, see
 * [CaptchaExtractor]) so the user never sees a login flow.
 */
@Inject
@SingleIn(AppScope::class)
public class OnlineSourceService internal constructor(
  @OnlineSourceEnabledStore private val enabledStore: DataStore<Boolean>,
  @OnlineSourceBaseUrlStore private val baseUrlStore: DataStore<String>,
  @OnlineSourceCredentialStore private val credentialStore: DataStore<String>,
  @OnlineSourceTokenStore private val tokenStore: DataStore<String>,
  @OnlineSourceBooksStore private val booksStore: DataStore<List<OnlineBook>>,
  private val client: OnlineSourceClient,
) {

  private val loginMutex = Mutex()
  private var cachedToken: String? = null
  private val chaptersCache = object : LinkedHashMap<String, List<OnlineChapter>>(0, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<OnlineChapter>>): Boolean {
      return size > 24
    }
  }

  /** Emits whether the online source is configured and switched on. */
  public val enabledFlow: Flow<Boolean> = enabledStore.data

  /** The list of search sources, only meaningful when enabled. */
  public suspend fun isConfigured(): Boolean {
    return enabledStore.data.first() &&
      baseUrlStore.data.first().isNotBlank() &&
      credentialStore.data.first().isNotBlank()
  }

  public suspend fun sources(): List<OnlineSourceInfo> {
    val (base, _) = authed()
    return withRelogin { client.interfaces(base, it) }
  }

  /**
   * Searches one source. [source] is either [OnlineSourceClient.SOURCE_MAIN]
   * or an interface name from [sources].
   */
  public suspend fun search(
    source: String,
    keyword: String,
  ): List<OnlineSearchResult> {
    val (base, _) = authed()
    return withRelogin {
      if (source == OnlineSourceClient.SOURCE_MAIN) {
        client.searchMain(base, it, keyword)
      } else {
        client.searchSource(base, it, source, keyword)
      }
    }
  }

  public suspend fun chapters(
    source: String,
    bookId: String,
  ): List<OnlineChapter> {
    val cacheKey = "$source::$bookId"
    synchronized(chaptersCache) {
      chaptersCache[cacheKey]?.let { return it }
    }
    val chapters = fetchChapters(source, bookId)
    synchronized(chaptersCache) {
      chaptersCache[cacheKey] = chapters
    }
    return chapters
  }

  /**
   * Re-fetches the chapter list of a book from the source, bypassing the
   * in-memory cache, and stores the fresh list there. Used when the user
   * asks for new chapters of a book that is still being updated.
   */
  public suspend fun refreshChapters(
    source: String,
    bookId: String,
  ): List<OnlineChapter> {
    val chapters = fetchChapters(source, bookId)
    synchronized(chaptersCache) {
      chaptersCache["$source::$bookId"] = chapters
    }
    return chapters
  }

  private suspend fun fetchChapters(
    source: String,
    bookId: String,
  ): List<OnlineChapter> {
    val (base, _) = authed()
    return withRelogin {
      if (source == OnlineSourceClient.SOURCE_MAIN) {
        val response = client.mainAlbumListResponse(base, it, bookId)
        if (!response.first) {
          throw OnlineSourceException(response.second ?: "the source returned no chapter list")
        }
        response.third
      } else {
        val response = client.sourceAlbumListResponse(base, it, source, bookId)
        if (!response.first) {
          throw OnlineSourceException(response.second ?: "the source returned no chapter list")
        }
        response.third
      }
    }
  }

  /** Resolves a temporary direct streaming url for one chapter (pluggable sources). */
  public suspend fun resolveDirectUrl(
    source: String,
    bookId: String,
    chapterId: String,
  ): String? {
    if (source == OnlineSourceClient.SOURCE_MAIN) return null
    val (base, _) = authed()
    return withRelogin { client.sourceAudio(base, it, source, bookId, chapterId) }
  }

  /** Submits a server side download and returns the task id. */
  public suspend fun submitDownload(
    bookId: String,
    startEpisode: Int,
    endEpisode: Int,
  ): String? {
    val (base, _) = authed()
    return withRelogin { client.submitBatch(base, it, bookId, startEpisode, endEpisode) }
  }

  public suspend fun downloadStatus(taskId: String): OnlineBatchStatus {
    val (base, _) = authed()
    return withRelogin { client.batchStatus(base, it, taskId) }
  }

  /** The albums already downloaded on the site (each with its files). */
  public suspend fun downloadedAlbums(): List<FilesAlbum> {
    val (base, _) = authed()
    return withRelogin { client.downloadedAlbums(base, it) }
  }

  /** The books added from the online source, most recently added first. */
  public fun shelf(): Flow<List<OnlineBook>> {
    return booksStore.data
  }

  /**
   * Adds or updates an online book (upsert by [OnlineBook.key]). Re-adding a
   * book that is already on the shelf keeps its playback position, its skip
   * settings and the durations measured from real streams: only the chapter
   * list itself is taken from the fresh copy.
   */
  public suspend fun addToShelf(book: OnlineBook) {
    booksStore.updateData { current ->
      val existing = current.firstOrNull { it.key == book.key }
      val merged = if (existing == null) {
        book
      } else {
        book.copy(
          currentChapterId = existing.currentChapterId,
          positionMs = existing.positionMs,
          skipIntroMs = existing.skipIntroMs,
          skipOutroMs = existing.skipOutroMs,
          chapters = mergeChapterDurations(existing.chapters, book.chapters),
        )
      }
      listOf(merged.copy(addedAt = System.currentTimeMillis())) +
        current.filterNot { it.key == book.key }
    }
  }

  /**
   * Takes the chapter list from [fresh] but keeps a duration the shelf
   * already measured when the fresh entry reports none, so re-adding a book
   * does not throw away corrected durations.
   */
  private fun mergeChapterDurations(
    old: List<OnlineChapter>,
    fresh: List<OnlineChapter>,
  ): List<OnlineChapter> {
    if (old.isEmpty()) return fresh
    val oldById = old.associateBy { it.id }
    return fresh.map { chapter ->
      val previous = oldById[chapter.id]
      if (previous != null && chapter.durationSeconds <= 0 && previous.durationSeconds > 0) {
        chapter.copy(durationSeconds = previous.durationSeconds)
      } else {
        chapter
      }
    }
  }

  /** Removes an online book. Only the record - server files stay untouched. */
  public suspend fun removeFromShelf(key: String) {
    booksStore.updateData { current ->
      current.filterNot { it.key == key }
    }
  }

  public suspend fun shelfBook(key: String): OnlineBook? {
    return booksStore.data.first().firstOrNull { it.key == key }
  }

  /**
   * Verifies a base url / credential pair by performing a real card key
   * login. Throws [OnlineSourceException] on failure; on success the token
   * is persisted so the next request is already authenticated.
   */
  public suspend fun verify(
    baseUrl: String,
    credential: String,
  ): String {
    val token = client.login(baseUrl, credential)
    tokenStore.updateData { token }
    cachedToken = token
    return token
  }

  /** Drops the cached token (e.g. after the server address changed). */
  public suspend fun invalidateToken() {
    tokenStore.updateData { "" }
    cachedToken = null
  }

  /** Builds the streaming url for a file downloaded on the site. */
  public suspend fun downloadedFileUrl(path: String): String {
    val base = baseUrlStore.data.first()
    return client.fileUrl(base, path)
  }

  /** Writes the settings coming from the preferences screen. */
  public suspend fun configure(
    enabled: Boolean,
    baseUrl: String,
    credential: String,
  ) {
    if (credential != credentialStore.data.first() || baseUrl != baseUrlStore.data.first()) {
      // credentials changed: drop the cached token
      tokenStore.updateData { "" }
      cachedToken = null
    }
    baseUrlStore.updateData { baseUrl.trim() }
    credentialStore.updateData { credential.trim() }
    enabledStore.updateData { enabled }
  }

  public fun baseUrlFlow(): Flow<String> = baseUrlStore.data

  private suspend fun authed(): Pair<String, String> {
    val base = baseUrlStore.data.first()
    val token = obtainToken()
    return base to token
  }

  private suspend fun obtainToken(): String {
    cachedToken?.let { if (it.isNotBlank()) return it }
    val stored = tokenStore.data.first()
    if (stored.isNotBlank()) {
      cachedToken = stored
      return stored
    }
    return login()
  }

  private suspend fun login(): String = loginMutex.withLock {
    val stored = tokenStore.data.first()
    if (stored.isNotBlank()) {
      cachedToken = stored
      return stored
    }
    val base = baseUrlStore.data.first()
    val credential = credentialStore.data.first()
    val token = client.login(base, credential)
    tokenStore.updateData { token }
    cachedToken = token
    token
  }

  /**
   * Runs [block]; on a 401 the token is refreshed once and the call retried.
   */
  private suspend fun <T> withRelogin(block: suspend (String) -> T): T {
    val token = obtainToken()
    return try {
      block(token)
    } catch (e: OnlineSourceException) {
      if (!e.requiresRelogin) throw e
      tokenStore.updateData { "" }
      cachedToken = null
      block(login())
    }
  }
}
