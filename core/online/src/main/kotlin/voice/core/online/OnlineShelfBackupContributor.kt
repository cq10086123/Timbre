package voice.core.online

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import voice.core.data.backup.ShelfBackupContribution
import voice.core.data.backup.ShelfBackupContributor
import voice.core.data.backup.ShelfBackupRestore
import voice.core.logging.api.Logger
import java.util.Base64

/**
 * Online source books on the shelf, for the shelf backup.
 *
 * These books never reach the Room table - they live in their own DataStore -
 * so the backup would drop them without this. Unlike a local book, an online
 * book is identified by `source::bookId`, which is the same on every device,
 * so restore can put a missing book back on the shelf instead of only merging
 * into an existing one. The chapter list is not part of the backup: it is
 * re-fetched from the source (and kept in [OnlineChapterStore]) the first time
 * the book is opened, and the saved chapter id survives because chapter ids
 * come from the source as well.
 *
 * The cover is carried as bytes. The shelf record of a book whose cover was
 * downloaded already points at `file:` - a path that means nothing on another
 * device - and the remote url it came from is not kept, so the image itself is
 * the only way to restore it.
 */
@Serializable
private data class OnlineBackupBook(
  val source: String,
  val bookId: String,
  val title: String = "",
  val author: String = "",
  val cover: String = "",
  /** The remote url the cover came from, kept when the image itself is missing. */
  val coverUrl: String? = null,
  /** Base64 of the cover file, empty when the record only had a remote url. */
  val coverImage: String? = null,
  val addedAt: Long = 0L,
  val currentChapterId: String = "",
  val positionInChapter: Long = 0L,
  val skipIntro: Long = 0L,
  val skipOutro: Long = 0L,
  val skipSilence: Boolean = false,
  val playbackSpeed: Float = 1f,
  val gain: Float = 0f,
)

internal class OnlineShelfBackupContributor(
  private val service: OnlineSourceService,
  private val coverStore: OnlineCoverStore,
) : ShelfBackupContributor {

  private val json: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
  }

  override val id: String = "online"

  override suspend fun save(): ShelfBackupContribution {
    val books = service.shelf().first()
    val entries = books.map { book ->
      val url = remoteCoverOf(book.cover, book.coverUrl)
      OnlineBackupBook(
        source = book.source,
        bookId = book.bookId,
        title = book.title,
        author = book.author,
        // a local file path is meaningless on another device, so only a url
        // goes into the record; the image itself is embedded separately
        cover = url.orEmpty(),
        coverUrl = url,
        coverImage = encodedCover(book),
        addedAt = book.addedAt,
        currentChapterId = book.currentChapterId,
        positionInChapter = book.positionMs,
        skipIntro = book.skipIntroMs,
        skipOutro = book.skipOutroMs,
        skipSilence = book.skipSilence,
        playbackSpeed = book.playbackSpeed,
        gain = book.gain,
      )
    }
    return ShelfBackupContribution(
      count = entries.size,
      payload = json.encodeToJsonElement(ListSerializer(OnlineBackupBook.serializer()), entries),
    )
  }

  override suspend fun restore(payload: JsonElement): ShelfBackupRestore {
    val entries = try {
      json.decodeFromJsonElement(ListSerializer(OnlineBackupBook.serializer()), payload)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Logger.w(e, "Could not read the online section of the backup")
      return ShelfBackupRestore(restored = 0, total = 0)
    }
    var restored = 0
    for (entry in entries) {
      val book = OnlineBook(
        source = entry.source,
        bookId = entry.bookId,
        title = entry.title,
        author = entry.author,
        cover = restoredCover(entry),
        coverUrl = remoteCoverOf(entry.cover, entry.coverUrl),
        addedAt = entry.addedAt,
        currentChapterId = entry.currentChapterId,
        positionMs = entry.positionInChapter,
        skipIntroMs = entry.skipIntro,
        skipOutroMs = entry.skipOutro,
        skipSilence = entry.skipSilence,
        playbackSpeed = entry.playbackSpeed,
        gain = entry.gain,
      )
      try {
        service.restoreShelfEntry(book)
        restored++
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Logger.w(e, "Could not restore the online book ${book.key}")
      }
    }
    return ShelfBackupRestore(restored = restored, total = entries.size)
  }

  /**
   * The stored cover file of [book] as base64, or null when the record still
   * carries a remote url (nothing to embed) or the file is too large to be
   * worth carrying in a JSON backup.
   */
  private suspend fun encodedCover(book: OnlineBook): String? {
    val file = coverStore.file(book.key) ?: return null
    val length = file.length()
    if (length <= 0L || length > MAX_COVER_BYTES) return null
    return runCatching {
      // java.util on purpose: android.util is a stub under the JVM unit tests
      Base64.getEncoder().encodeToString(file.readBytes())
    }.onFailure {
      Logger.w(it, "Could not read the online cover of ${book.key}")
    }.getOrNull()
  }

  /**
   * Writes the embedded cover back and points the record at the new file. A
   * backup without an image falls back to the remote url it carries - never to
   * the stored `file:` path of the device that made it, which resolves to
   * nothing here.
   */
  private suspend fun restoredCover(entry: OnlineBackupBook): String {
    val remote = remoteCoverOf(entry.cover, entry.coverUrl).orEmpty()
    val image = entry.coverImage ?: return remote
    val bytes = runCatching { Base64.getDecoder().decode(image) }
      .onFailure { Logger.w(it, "Could not decode the cover of ${entry.source}::${entry.bookId}") }
      .getOrNull()
      ?: return remote
    val file = runCatching {
      coverStore.write(OnlineBookRef(entry.source, entry.bookId).key, bytes)
    }.onFailure {
      Logger.w(it, "Could not store the restored cover of ${entry.source}::${entry.bookId}")
    }.getOrNull()
    return file?.toURI()?.toString() ?: remote
  }

  /** The remote url of an entry, from either field the backup may carry it in. */
  private fun remoteCoverOf(
    cover: String,
    url: String?,
  ): String? {
    return url?.takeIf { it.startsWith("http") } ?: cover.takeIf { it.startsWith("http") }
  }

  private companion object {
    /** Covers are a few tens of KB; a bigger file is not worth a JSON blob. */
    const val MAX_COVER_BYTES: Long = 512L * 1024L
  }
}

@ContributesTo(AppScope::class)
public interface OnlineBackupGraph {

  @Provides
  @IntoSet
  public fun onlineShelfBackupContributor(
    service: OnlineSourceService,
    coverStore: OnlineCoverStore,
  ): ShelfBackupContributor {
    return OnlineShelfBackupContributor(service, coverStore)
  }
}
