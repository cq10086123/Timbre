package voice.core.webdav

import android.net.Uri
import androidx.datastore.core.DataStore
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import voice.core.data.Book
import voice.core.data.BookId
import voice.core.data.backup.BookImportState
import voice.core.data.backup.ShelfBackupContributor
import voice.core.data.repo.BookRepository
import voice.core.logging.api.Logger
import java.time.Instant

/**
 * Uploads the shelf ("which books, how far in") to a WebDAV folder and pulls it
 * back.
 *
 * Local and WebDAV books are read from [BookRepository] and only merged into
 * books that already exist on this device: a book's chapters come from a local
 * folder or a WebDAV source, so restoring does not recreate them. A book that
 * is missing is counted in [WebDavRestoreResult.Success.total] but not in
 * [WebDavRestoreResult.Success.restored].
 *
 * Sections outside the book table (books from an online source) are delegated
 * to the injected [ShelfBackupContributor]s, which can also put a book back on
 * the shelf because their identity is stable across devices.
 *
 * A backup only runs once the user picked a folder, and never writes an empty
 * or half-indexed shelf over a previous backup: both would silently destroy it.
 */
@SingleIn(AppScope::class)
@Inject
public class WebDavBackupService(
  private val client: WebDavClient,
  private val resolver: WebDavCredentialResolver,
  private val bookRepository: BookRepository,
  private val importState: BookImportState,
  @WebDavServersStore private val serversStore: DataStore<List<WebDavServer>>,
  @WebDavBackupTargetStore private val targetStore: DataStore<WebDavBackupTarget>,
  private val contributors: Set<@JvmSuppressWildcards ShelfBackupContributor>,
) {

  private val json: Json = Json {
    ignoreUnknownKeys = true
    prettyPrint = true
  }

  /** The folder backups are read from and written to, null until one is picked. */
  public fun location(): Flow<WebDavBackupLocation?> {
    return combine(targetStore.data, serversStore.data) { target, servers ->
      locationOf(target, servers)
    }
  }

  /** Reads the location once, for callers that are not collecting a flow. */
  public suspend fun currentLocation(): WebDavBackupLocation? {
    return locationOf(targetStore.data.first(), serversStore.data.first())
  }

  /** Points the backup at [folderUrl] on [serverId]; both backup and restore follow it. */
  public suspend fun setLocation(
    serverId: String,
    folderUrl: String,
  ) {
    targetStore.updateData { current ->
      current.copy(serverId = serverId, folderUrl = folderUrl.trimEnd('/'))
    }
  }

  /** Remembers [label] so the next backup dialog can offer it again. */
  public suspend fun rememberLabel(label: String?) {
    targetStore.updateData { current -> current.copy(label = label) }
  }

  /** The label of the last backup that was written, offered again next time. */
  public suspend fun lastLabel(): String? {
    return targetStore.data.first().label
  }

  /** Whether a backup can run at all: a server plus a folder the user picked. */
  public suspend fun targetStatus(): WebDavBackupTargetStatus {
    return when (resolveTarget()) {
      is Target.Ready -> WebDavBackupTargetStatus.Ready
      Target.NoServer -> WebDavBackupTargetStatus.NoServer
      Target.NoFolder -> WebDavBackupTargetStatus.NoFolder
    }
  }

  /**
   * Books a backup taken right now would miss: their import is still running
   * (only the first batch of chapters is stored) or their stored chapters are
   * incomplete. Asked before the label dialog as well, so the user is not
   * asked to pick a label for a backup that would never be written.
   */
  public suspend fun pendingImports(): Int = withContext(Dispatchers.IO) {
    pendingImports(bookRepository.all())
  }

  private suspend fun pendingImports(books: List<Book>): Int {
    val importing = unfinishedImports(
      stored = books.associate { it.id to it.content.chapters.size },
      pending = importState.chapterTotals(),
    )
    if (importing > 0) return importing
    return bookRepository.incompleteCount()
  }

  /**
   * Writes the shelf to the backup file of [label]; a null or blank label is
   * the unnamed default backup.
   */
  public suspend fun backup(label: String? = null): WebDavBackupResult = withContext(Dispatchers.IO) {
    val target = when (val target = resolveTarget()) {
      is Target.Ready -> target
      Target.NoServer -> return@withContext WebDavBackupResult.NoServer
      Target.NoFolder -> return@withContext WebDavBackupResult.NoFolder
    }
    val books = bookRepository.all()
    val extras = linkedMapOf<String, JsonElement>()
    var extraCount = 0
    for (contributor in contributors) {
      // one broken section must not lose the rest of the shelf
      val contribution = try {
        contributor.save()
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Logger.w(e, "WebDAV backup skipped section ${contributor.id}")
        continue
      }
      if (contribution.count == 0) continue
      extras[contributor.id] = contribution.payload
      extraCount += contribution.count
    }
    // books whose chapters are not stored yet are skipped by all(): a backup
    // taken now could never be restored, so wait for the index instead
    val pending = pendingImports(books)
    if (pending > 0) {
      return@withContext WebDavBackupResult.BooksLoading(pending)
    }
    // checked after the contributors ran: online books alone are a shelf too,
    // and writing an empty file would overwrite a usable backup with nothing
    if (books.isEmpty() && extraCount == 0) {
      return@withContext WebDavBackupResult.EmptyShelf
    }
    val payload = WebDavBackup(
      createdAt = Instant.now().toString(),
      books = books.map { it.toBackupEntry() },
      extras = extras,
    )
    val bytes = json.encodeToString(WebDavBackup.serializer(), payload).toByteArray(Charsets.UTF_8)
    try {
      client.put(
        server = target.server,
        password = target.password,
        url = backupUrl(target.location, label),
        bytes = bytes,
        contentType = WebDavBackup.CONTENT_TYPE,
      )
      // a listing cached before this write would hide the new file
      client.invalidateListingsForServer(target.server.id)
      Logger.d("WebDAV backup wrote ${books.size} books (+$extraCount) to ${backupUrl(target.location, label)}")
      WebDavBackupResult.Success(
        bookCount = books.size + extraCount,
        serverName = target.location.serverName,
        label = label?.trim()?.ifEmpty { null },
      )
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Logger.w(e, "WebDAV backup failed")
      WebDavBackupResult.Failure(e.describe())
    }
  }

  /** Merges the backup file of [label] back into this device. */
  public suspend fun restore(label: String? = null): WebDavRestoreResult = withContext(Dispatchers.IO) {
    val target = when (val target = resolveTarget()) {
      is Target.Ready -> target
      Target.NoServer -> return@withContext WebDavRestoreResult.NoServer
      Target.NoFolder -> return@withContext WebDavRestoreResult.NoFolder
    }
    val bytes = try {
      client.getBytes(target.server, target.password, backupUrl(target.location, label))
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Logger.w(e, "WebDAV restore failed")
      return@withContext WebDavRestoreResult.Failure(e.describe())
    } ?: return@withContext WebDavRestoreResult.NoBackupFile

    val payload = try {
      json.decodeFromString(WebDavBackup.serializer(), bytes.toString(Charsets.UTF_8))
    } catch (e: Exception) {
      Logger.w(e, "Could not parse the WebDAV backup")
      return@withContext WebDavRestoreResult.Failure(e.describe())
    }

    val local = bookRepository.all()
    val byId = local.associateBy { it.id.value }
    val byFallback = local.groupBy { it.fallbackKey() }
    var restored = 0
    for (entry in payload.books) {
      // an id is exact and preferred; the name/duration key covers devices
      // where the same book got a different uri
      val book = byId[entry.id] ?: byFallback[entry.fallbackKey()]?.firstOrNull() ?: continue
      apply(book, entry)
      restored++
    }
    var total = payload.books.size
    for ((id, section) in payload.extras) {
      val contributor = contributors.firstOrNull { it.id == id } ?: continue
      val result = try {
        contributor.restore(section)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Logger.w(e, "WebDAV restore skipped section $id")
        continue
      }
      restored += result.restored
      total += result.total
    }
    Logger.d("WebDAV restore merged $restored of $total books")
    WebDavRestoreResult.Success(
      restored = restored,
      total = total,
      serverName = target.location.serverName,
      label = label?.trim()?.ifEmpty { null },
    )
  }

  /** The backups that exist in the configured folder, newest first. */
  public suspend fun backups(): WebDavBackupListResult = withContext(Dispatchers.IO) {
    val target = when (val target = resolveTarget()) {
      is Target.Ready -> target
      Target.NoServer -> return@withContext WebDavBackupListResult.NoServer
      Target.NoFolder -> return@withContext WebDavBackupListResult.NoFolder
    }
    try {
      // the folder may have been written from another device since it was listed
      client.invalidateListingsForServer(target.server.id)
      val items = client.list(target.server, target.password, target.location.folderUrl)
        .mapNotNull { resource ->
          // the URL is authoritative: DA*:displayname* is whatever the server
          // wants it to be and not always the file name
          val fileName = Uri.decode(resource.url).trimEnd('/').substringAfterLast('/')
          if (!WebDavBackup.isBackupFile(fileName)) {
            null
          } else {
            WebDavBackupItem(
              label = WebDavBackup.labelOf(fileName),
              fileName = fileName,
              lastModified = resource.lastModified,
              size = resource.contentLength,
            )
          }
        }
        .sortedByDescending { it.lastModified }
      WebDavBackupListResult.Success(items = items, serverName = target.location.serverName)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Logger.w(e, "Could not list the WebDAV backups")
      WebDavBackupListResult.Failure(e.describe())
    }
  }

  private suspend fun apply(
    book: Book,
    entry: WebDavBackupBook,
  ) {
    // the stored chapter may be gone (a file was replaced), in that case the
    // first chapter is the only position that is safe to write
    val chapter = book.content.chapters.firstOrNull { it.value == entry.currentChapter }
      ?: book.content.chapters.firstOrNull()
      ?: return
    bookRepository.updatePlaybackPosition(
      id = book.id,
      currentChapter = chapter,
      positionInChapter = entry.positionInChapter.coerceAtLeast(0L),
      persist = true,
    )
    // updatePlaybackPosition stamps lastPlayedAt with "now": writing the
    // stored value back afterwards keeps the recently-played order intact
    val lastPlayedAt = runCatching { Instant.parse(entry.lastPlayedAt) }.getOrNull()
    bookRepository.updateBook(book.id) { content ->
      content.copy(
        isActive = entry.isActive,
        playbackSpeed = entry.playbackSpeed,
        skipSilence = entry.skipSilence,
        skipIntro = entry.skipIntro,
        skipOutro = entry.skipOutro,
        gain = entry.gain,
        lastPlayedAt = lastPlayedAt ?: content.lastPlayedAt,
      )
    }
  }

  private fun Book.toBackupEntry(): WebDavBackupBook {
    return WebDavBackupBook(
      id = id.value,
      name = content.name,
      author = content.author,
      isActive = content.isActive,
      currentChapter = content.currentChapter.value,
      positionInChapter = content.positionInChapter,
      lastPlayedAt = content.lastPlayedAt.toString(),
      playbackSpeed = content.playbackSpeed,
      skipSilence = content.skipSilence,
      skipIntro = content.skipIntro,
      skipOutro = content.skipOutro,
      gain = content.gain,
      chapterCount = content.chapters.size,
      totalDurationMs = duration,
    )
  }

  private fun Book.fallbackKey(): String {
    return "${content.name}|${content.chapters.size}|$duration"
  }

  private fun WebDavBackupBook.fallbackKey(): String {
    return "$name|$chapterCount|$totalDurationMs"
  }

  /**
   * A folder only counts while its server is still configured; without a
   * picked folder there is no location at all, a backup never guesses one.
   */
  private fun locationOf(
    target: WebDavBackupTarget,
    servers: List<WebDavServer>,
  ): WebDavBackupLocation? {
    if (!target.isConfigured) return null
    val server = servers.firstOrNull { it.id == target.serverId } ?: return null
    return WebDavBackupLocation(
      serverId = server.id,
      serverName = server.name,
      serverBaseUrl = server.baseUrl.trimEnd('/'),
      folderUrl = target.folderUrl.orEmpty().trimEnd('/'),
    )
  }

  private suspend fun resolveTarget(): Target {
    val target = targetStore.data.first()
    val servers = serversStore.data.first()
    if (servers.isEmpty()) return Target.NoServer
    if (!target.isConfigured) return Target.NoFolder
    val server = servers.firstOrNull { it.id == target.serverId } ?: return Target.NoFolder
    // a password that cannot be decrypted (reinstall) is the same dead end as
    // no server at all: the folder cannot be reached either way
    val resolved = resolver.byId(server.id) ?: return Target.NoServer
    return Target.Ready(
      location = WebDavBackupLocation(
        serverId = server.id,
        serverName = server.name,
        serverBaseUrl = server.baseUrl.trimEnd('/'),
        folderUrl = target.folderUrl.orEmpty().trimEnd('/'),
      ),
      server = resolved.server,
      password = resolved.password,
    )
  }

  private fun backupUrl(
    location: WebDavBackupLocation,
    label: String?,
  ): String {
    val name = Uri.encode(WebDavBackup.fileName(label))
    return "${location.folderUrl.trimEnd('/')}/$name"
  }

  private fun Exception.describe(): String {
    return message?.takeIf { it.isNotBlank() } ?: this::class.simpleName ?: "unknown error"
  }

  private sealed interface Target {
    data object NoServer : Target

    data object NoFolder : Target

    class Ready(
      val location: WebDavBackupLocation,
      val server: WebDavServer,
      val password: String,
    ) : Target
  }
}

/**
 * How many books the importer is still working on, as opposed to books a
 * refresh re-reads: a book counts when the importer found more chapters than
 * are stored, or when it is not stored at all yet.
 *
 * The importer writes the first analyzed batch right away so the book shows up
 * on the shelf, so a book that is still importing looks complete at the
 * database level - its chapter list simply holds the first 20 of 160 chapters.
 * A backup taken then would carry that truncated book, and restoring it could
 * not put it back.
 */
internal fun unfinishedImports(
  stored: Map<BookId, Int>,
  pending: Map<BookId, Int>,
): Int {
  return pending.count { (id, total) ->
    val storedChapters = stored[id] ?: return@count true
    total > storedChapters
  }
}
