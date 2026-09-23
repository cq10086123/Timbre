package voice.core.playback.session

import androidx.media3.common.Player
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import voice.core.data.Book
import voice.core.data.BookContent
import voice.core.data.BookId
import voice.core.data.ChapterId
import voice.core.data.repo.BookContentRepo
import voice.core.data.repo.BookRepository
import voice.core.logging.api.Logger
import voice.core.online.OnlinePlaybackCatalog
import voice.core.online.OnlineUri
import voice.core.playback.di.PlaybackScope

/**
 * Appends the chapters of a book to its playlist while the book is still being
 * imported, so a book can be listened to before the import finished.
 *
 * Chapters are imported in their natural order, which makes the playlist of a
 * partially imported book a prefix of the final playlist. New chapters can
 * therefore be added at the end without touching the chapter that is currently
 * playing.
 *
 * Online books grow the same way when their chapter list is refreshed, so the
 * online shelf is watched as well and refreshed chapters are appended without
 * interrupting playback.
 */
@Inject
@SingleIn(PlaybackScope::class)
class BookPlaylistSynchronizer(
  private val bookRepository: BookRepository,
  private val contentRepo: BookContentRepo,
  private val onlinePlaybackCatalog: OnlinePlaybackCatalog,
  private val mediaItemProvider: MediaItemProvider,
  private val scope: CoroutineScope,
) {

  private var player: Player? = null
  private var job: Job? = null
  private var onlineJob: Job? = null
  private var lastMediaId: String? = null
  private var lastBookId: BookId? = null

  /**
   * Serializes playlist surgery: the room and the online collectors run on
   * their own coroutines and must never rebuild the playlist interleaved
   * (e.g. while switching between a local and an online book).
   */
  private val syncMutex = Mutex()

  /** The chapters the playlist was last synchronized with. */
  private var syncedChapters: List<ChapterId>? = null

  /**
   * The media ids the playlist was last built from, in playlist order. Keeping
   * them lets every append of a partially imported book verify the existing
   * playlist and build only the new items instead of the whole book again,
   * which for books with thousands of chapters is a lot of work per import
   * batch on the main thread.
   */
  private var syncedItemIds: List<String>? = null

  fun attachTo(player: Player) {
    this.player = player
    job?.cancel()
    onlineJob?.cancel()
    job = scope.launch {
      contentRepo.flow().collectLatest { contents ->
        val player = this@BookPlaylistSynchronizer.player ?: return@collectLatest
        val bookId = player.currentBookId() ?: return@collectLatest
        val content = contents.firstOrNull { it.id == bookId } ?: return@collectLatest
        // positions are published several times per second and share the chapter
        // list instance, so this is the cheap check for "nothing was imported"
        if (content.chapters === syncedChapters || content.chapters == syncedChapters) {
          return@collectLatest
        }
        syncMutex.withLock {
          sync(player, content)
        }
      }
    }
    onlineJob = scope.launch {
      // the online shelf has no room row: refreshed chapters arrive here.
      // The chapter ids are compared before the book is assembled, so the
      // frequent position persists (which also emit) stay cheap.
      onlinePlaybackCatalog.shelfBooks().collectLatest { books ->
        val player = this@BookPlaylistSynchronizer.player ?: return@collectLatest
        val bookId = player.currentBookId() ?: return@collectLatest
        if (!onlinePlaybackCatalog.isOnlineBookId(bookId)) return@collectLatest
        val online = books.firstOrNull { OnlineUri.buildBookUri(it.source, it.bookId) == bookId.value }
          ?: return@collectLatest
        val chapterIds = online.chapters.map { ChapterId(OnlineUri.build(online.source, online.bookId, it.id)) }
        if (chapterIds == syncedChapters) return@collectLatest
        val book = onlinePlaybackCatalog.book(bookId) ?: return@collectLatest
        syncMutex.withLock {
          syncBook(player, book)
        }
      }
    }
  }

  private suspend fun sync(
    player: Player,
    content: BookContent,
  ) {
    val playlistSize = player.mediaItemCount
    if (playlistSize == 0) {
      // no book is loaded, the next playback start builds the playlist anyway
      syncedChapters = null
      syncedItemIds = null
      return
    }
    val book = bookRepository.get(content.id) ?: return
    syncBook(player, book)
  }

  private suspend fun syncBook(
    player: Player,
    book: Book,
  ) {
    val playlistSize = player.mediaItemCount
    if (playlistSize == 0) {
      // no book is loaded, the next playback start builds the playlist anyway
      syncedChapters = null
      syncedItemIds = null
      return
    }
    val itemCount = book.chapters.sumOf { it.chapterMarks.size }
    if (itemCount == playlistSize) {
      syncedChapters = book.content.chapters
      return
    }

    val expectedIds = syncedItemIds
      ?: mediaItemProvider.playbackItemIds(book, limit = playlistSize)
    val isPrefix = expectedIds.size == playlistSize &&
      expectedIds.indices.all { index ->
        player.getMediaItemAt(index).mediaId == expectedIds[index]
      }

    if (isPrefix) {
      val appendedItems = mediaItemProvider.playbackItems(book, fromItemIndex = playlistSize)
      if (appendedItems.isNotEmpty()) {
        val playlistRanOut = player.playbackState == Player.STATE_ENDED && player.playWhenReady
        player.addMediaItems(playlistSize, appendedItems)
        syncedItemIds = expectedIds + appendedItems.map { it.mediaId }
        syncedChapters = book.content.chapters
        Logger.i("appended ${appendedItems.size} chapters of ${book.id} while playing")
        if (playlistRanOut) {
          // the import wasn't done when the last known chapter ended
          player.seekTo(playlistSize, 0)
          player.play()
        }
        return
      }
      // prefix already covers everything known — VoicePlayer may still be
      // expanding the same way; do not rebuild
      syncedItemIds = expectedIds
      syncedChapters = book.content.chapters
      return
    }

    // the chapters changed in a way that can't be appended. Keep the current
    // chapter and its position, otherwise the playback would restart.
    val currentMediaId = player.currentMediaItem?.mediaId ?: return
    val items = mediaItemProvider.playbackItems(book)
    val currentIndex = items.indexOfFirst { it.mediaId == currentMediaId }
    if (currentIndex == -1) {
      Logger.w("Could not synchronize the playlist of ${book.id}")
      return
    }
    player.setMediaItems(items, currentIndex, player.currentPosition)
    syncedItemIds = items.map { it.mediaId }
    syncedChapters = book.content.chapters
  }

  private fun Player.currentBookId(): BookId? {
    val mediaId = currentMediaItem?.mediaId ?: return null
    if (mediaId != lastMediaId) {
      lastMediaId = mediaId
      lastBookId = mediaId.toMediaIdOrNull()?.bookId
    }
    return lastBookId
  }
}
