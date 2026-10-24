package voice.core.online

import androidx.datastore.core.DataStore
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import voice.core.common.DispatcherProvider
import voice.core.logging.api.Logger
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

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
  private val chapterStore: OnlineChapterStore,
  private val coverStore: OnlineCoverStore,
  @OnlineSourceStreamingClient private val httpClient: OkHttpClient,
  private val client: OnlineSourceClient,
  dispatcherProvider: DispatcherProvider,
  private val extensionSources: Set<@JvmSuppressWildcards ExtensionOnlineSource>,
) {

  /** Background cover downloads. Writes go through the stores' own locks, so
   *  concurrent downloads for different books stay safe. */
  private val coverScope = CoroutineScope(SupervisorJob() + dispatcherProvider.io)

  /** The streaming client has no call timeout on purpose (a chapter stays
   *  open); a cover download must finish, so it gets its own deadline while
   *  sharing the connection pool. */
  private val coverHttpClient = httpClient.newBuilder()
    .callTimeout(15, TimeUnit.SECONDS)
    .build()
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
    return isServerConfigured() || extensionSources.any { it.hasEnabledSources() }
  }

  private suspend fun isServerConfigured(): Boolean {
    return enabledStore.data.first() &&
      baseUrlStore.data.first().isNotBlank() &&
      credentialStore.data.first().isNotBlank()
  }

  private fun extensionBackendFor(source: String): ExtensionOnlineSource? {
    return extensionSources.firstOrNull { it.handles(source) }
  }

  public suspend fun sources(): List<OnlineSourceInfo> {
    // extension sources need no server: list them first, and never let a
    // server failure (or missing configuration) hide them
    val extensionInfos = extensionSources
      .filter { it.hasEnabledSources() }
      .flatMap { it.enabledSourceInfos() }
    val serverInfos = if (isServerConfigured()) {
      runCatching {
        val (base, _) = authed()
        withRelogin { client.interfaces(base, it) }
      }.getOrDefault(emptyList())
    } else {
      emptyList()
    }
    return extensionInfos + serverInfos
  }

  /**
   * Searches one source. [source] is an interface name from [sources]
   * or a `jdr:` prefixed extension source.
   */
  public suspend fun search(
    source: String,
    keyword: String,
  ): List<OnlineSearchResult> {
    val backend = extensionBackendFor(source)
    if (backend != null) {
      return backend.search(source, keyword)
    }
    val (base, _) = authed()
    return withRelogin {
      client.searchSource(base, it, source, keyword)
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
    val backend = extensionBackendFor(source)
    if (backend != null) {
      return backend.chapters(source, bookId)
    }
    val (base, _) = authed()
    return withRelogin {
      val response = client.sourceAlbumListResponse(base, it, source, bookId)
      if (!response.first) {
        throw OnlineSourceException(response.second ?: "the source returned no chapter list")
      }
      response.third
    }
  }

  /**
   * Resolves a temporary direct streaming url (plus optional headers) for one chapter.
   *
   * [order] is the chapter's position in the album, forwarded to extension
   * sources only: their audio stage often accepts an episode number instead of
   * the chapter id, and the extras collected while listing chapters do not
   * survive a cold start. The built-in server resolves the number itself.
   */
  public suspend fun resolveDirectUrl(
    source: String,
    bookId: String,
    chapterId: String,
    order: Int? = null,
  ): ResolvedStream? {
    val backend = extensionBackendFor(source)
    if (backend != null) {
      return backend.resolveDirectUrl(source, bookId, chapterId, order)
    }
    val (base, _) = authed()
    return withRelogin { client.sourceAudio(base, it, source, bookId, chapterId)?.let(::ResolvedStream) }
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
   *
   * The chapter list is persisted in [chapterStore], not in the shelf record:
   * the shelf JSON stays small and position saves do not rewrite the chapters.
   */
  public suspend fun addToShelf(book: OnlineBook) {
    if (book.chapters.isNotEmpty()) {
      // merged against the freshest stored list under the store lock; a disk
      // failure must not abort the shelf add - the chapters then simply stay
      // as they were and playback falls back to the source
      runCatching {
        chapterStore.update(book.key) { stored ->
          mergeChapterDurations(stored, book.chapters)
        }
      }.onFailure { Logger.w("Failed to store the chapter list of ${book.key}: $it") }
    }
    booksStore.updateData { current ->
      val existing = current.firstOrNull { it.key == book.key }
      // the url a cover was downloaded from outlives the file it produced:
      // keep it so a deleted file can be fetched again
      val coverUrl = book.cover.takeIf { it.startsWith("http") }
        ?: existing?.coverUrl
        ?: book.coverUrl
      val merged = if (existing == null) {
        book.copy(coverUrl = coverUrl)
      } else {
        book.copy(
          coverUrl = coverUrl,
          currentChapterId = existing.currentChapterId,
          positionMs = existing.positionMs,
          skipIntroMs = existing.skipIntroMs,
          skipOutroMs = existing.skipOutroMs,
          skipSilence = existing.skipSilence,
          playbackSpeed = existing.playbackSpeed,
          gain = existing.gain,
        )
      }
      // a fresh copy without chapters must not wipe the stored list: it would
      // break the offline playback of a book whose source forgot the chapters
      listOf(merged.copy(chapters = emptyList(), addedAt = System.currentTimeMillis())) +
        current.filterNot { it.key == book.key }
    }
    if (book.cover.startsWith("http")) {
      coverScope.launch {
        rewriteCoverToLocalFile(book.key, book.cover)
      }
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

  /**
   * Upserts a shelf entry coming from a backup. Unlike [addToShelf], which is
   * meant for re-adds from a search result and keeps what the device already
   * has, this one writes the backed-up position and settings over the stored
   * ones. A book that is already on the shelf keeps its own title, cover and
   * added time - only the playback state comes from the backup.
   */
  public suspend fun restoreShelfEntry(book: OnlineBook) {
    val backupCover = usableCover(book.key, book.cover, book.coverUrl)
    booksStore.updateData { current ->
      val existing = current.firstOrNull { it.key == book.key }
      val merged = if (existing == null) {
        book.copy(cover = backupCover, chapters = emptyList())
      } else {
        existing.copy(
          // the backup carries a cover of its own (a file it wrote, or the
          // remote url); keep the stored one only when the backup has none
          cover = backupCover.ifBlank { usableCover(book.key, existing.cover, existing.coverUrl) },
          coverUrl = book.coverUrl ?: existing.coverUrl,
          currentChapterId = book.currentChapterId,
          positionMs = book.positionMs,
          skipIntroMs = book.skipIntroMs,
          skipOutroMs = book.skipOutroMs,
          skipSilence = book.skipSilence,
          playbackSpeed = book.playbackSpeed,
          gain = book.gain,
        )
      }
      // insert where the added time belongs instead of at the front, so a
      // restore does not shuffle the shelf
      val without = current.filterNot { it.key == book.key }
      val index = without.indexOfFirst { it.addedAt <= merged.addedAt }
      without.toMutableList().apply { add(if (index < 0) size else index, merged) }
    }
  }

  /**
   * A cover value that means something on this device: a remote url, or a
   * local file that is really there. A path recorded by another device - or
   * one whose file was deleted when the book left the shelf - is dropped in
   * favour of [url], so it neither renders blank nor travels into the next
   * backup again.
   */
  private suspend fun usableCover(
    key: String,
    cover: String,
    url: String? = null,
  ): String {
    if (cover.startsWith("http")) return cover
    if (cover.startsWith("file:") && coverStore.file(key) != null) return cover
    return url?.takeIf { it.startsWith("http") }.orEmpty()
  }

  /** Removes an online book. Only the record - server files stay untouched. */
  public suspend fun removeFromShelf(key: String) {
    booksStore.updateData { current ->
      current.filterNot { it.key == key }
    }
    chapterStore.remove(key)
    coverStore.remove(key)
  }

  /**
   * Downloads covers for shelf records that still point at a remote url. Runs
   * at app start, one cover at a time; a book whose cover cannot be fetched
   * right now simply keeps its remote url and is retried next start.
   *
   * A record whose local file is gone (the book was removed from the shelf and
   * restored, or the folder was cleaned) goes back to the url it came from, so
   * the cover shows again and gets localized by the next run.
   */
  public suspend fun backfillCovers() {
    val books = runCatching { booksStore.data.first() }.getOrDefault(emptyList())
    for (book in books) {
      val existing = coverStore.file(book.key)
      if (book.cover.startsWith("http")) {
        // a file may already exist while the record is still remote (a previous
        // rewrite failed after the download): serve it without re-downloading
        if (existing != null) {
          rewriteRecordCover(book.key, existing)
        } else {
          rewriteCoverToLocalFile(book.key, book.cover)
        }
        continue
      }
      if (existing != null) continue
      val url = book.coverUrl?.takeIf { it.startsWith("http") } ?: continue
      fallBackToRemoteCover(book.key, url)
    }
  }

  /** Points a record with a missing cover file at [url], then localizes it. */
  private suspend fun fallBackToRemoteCover(
    key: String,
    url: String,
  ) {
    runCatching {
      booksStore.updateData { books ->
        books.map { book -> if (book.key == key) book.copy(cover = url) else book }
      }
    }.onFailure { Logger.w("Could not fall back to the remote cover of $key: $it") }
    rewriteCoverToLocalFile(key, url)
  }

  /**
   * Downloads [url] and rewrites the shelf record's cover to the local file,
   * so covers load from disk instead of the network whenever Coil's shared
   * image cache has evicted them. A failure keeps the remote url: the cover
   * then loads as before and the next start retries via [backfillCovers].
   */
  private suspend fun rewriteCoverToLocalFile(
    key: String,
    url: String,
  ) {
    val bytes = runCatching { downloadCoverBytes(url) }
      .onFailure { Logger.w("Could not download the cover of $key: $it") }
      .getOrNull()
      ?: return
    val stored = runCatching { coverStore.write(key, bytes) }
      .onFailure { Logger.w("Could not store the cover of $key: $it") }
      .getOrNull()
      ?: return
    rewriteRecordCover(key, stored, expectedRemoteUrl = url)
  }

  /**
   * Points the shelf record at the local cover file - but only while the
   * record still carries a remote url (and for downloads: the exact url the
   * file came from), so a re-add with a fresher cover is not rolled back by a
   * slow download, and an already local record is left alone.
   */
  private suspend fun rewriteRecordCover(
    key: String,
    file: File,
    expectedRemoteUrl: String? = null,
  ) {
    runCatching {
      booksStore.updateData { books ->
        books.map { book ->
          val matches = book.key == key &&
            book.cover.startsWith("http") &&
            (expectedRemoteUrl == null || book.cover == expectedRemoteUrl)
          // the url stays next to the file: [backfillCovers] needs it when
          // the file is gone
          if (matches) book.copy(cover = file.toURI().toString(), coverUrl = book.cover) else book
        }
      }
    }.onFailure { Logger.w("Could not rewrite the cover of $key to the local file: $it") }
  }

  private suspend fun downloadCoverBytes(url: String): ByteArray? {
    return withContext(Dispatchers.IO) {
      coverHttpClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
        if (!response.isSuccessful) return@withContext null
        val input = response.body.byteStream()
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(8_192)
        var total = 0
        while (true) {
          val read = input.read(chunk)
          if (read < 0) break
          total += read
          if (total > MAX_COVER_BYTES) return@withContext null
          out.write(chunk, 0, read)
        }
        val bytes = out.toByteArray()
        // an error page must not replace a cover: Coil could not decode it and
        // the record would stay pointed at an undecodable file forever
        if (!looksLikeImage(bytes)) return@withContext null
        bytes
      }
    }
  }

  /** Common image magic numbers; html/xml error pages are rejected. Unknown
   *  binary formats pass - Coil sniffs them exactly like it does today. */
  private fun looksLikeImage(bytes: ByteArray): Boolean {
    if (bytes.size < 12) return false
    val b0 = bytes[0].toInt() and 0xFF
    val b1 = bytes[1].toInt() and 0xFF
    return when {
      b0 == 0xFF && b1 == 0xD8 -> true // jpeg
      b0 == 0x89 && b1 == 0x50 -> true // png
      b0 == 0x47 && b1 == 0x49 -> true // gif
      b0 == 0x42 && b1 == 0x4D -> true // bmp
      b0 == 0x52 && (bytes[8].toInt() and 0xFF) == 0x57 -> true // webp (RIFF....WEBP)
      else -> !String(bytes, 0, 8, Charsets.US_ASCII).contains('<')
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

  private companion object {

    /** Covers are small; anything bigger is not a cover (or a hostile source). */
    private const val MAX_COVER_BYTES = 10 * 1024 * 1024
  }
}
