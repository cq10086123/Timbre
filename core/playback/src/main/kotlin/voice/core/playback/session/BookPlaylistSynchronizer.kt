package voice.core.playback.session

import androidx.media3.common.Player
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import voice.core.data.BookContent
import voice.core.data.BookId
import voice.core.data.ChapterId
import voice.core.data.repo.BookContentRepo
import voice.core.data.repo.BookRepository
import voice.core.logging.api.Logger
import voice.core.playback.di.PlaybackScope

/**
 * Appends the chapters of a book to its playlist while the book is still being
 * imported, so a book can be listened to before the import finished.
 *
 * Chapters are imported in their natural order, which makes the playlist of a
 * partially imported book a prefix of the final playlist. New chapters can
 * therefore be added at the end without touching the chapter that is currently
 * playing.
 */
@Inject
@SingleIn(PlaybackScope::class)
class BookPlaylistSynchronizer(
  private val bookRepository: BookRepository,
  private val contentRepo: BookContentRepo,
  private val mediaItemProvider: MediaItemProvider,
  private val scope: CoroutineScope,
) {

  private var player: Player? = null
  private var job: Job? = null
  private var lastMediaId: String? = null
  private var lastBookId: BookId? = null

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
        sync(player, content)
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
    }

    // VoicePlayer may install a mid-book resume window (not a prefix of the
    // full playlist). Replacing it with the full list here would undo that
    // optimization on the next content emission and race expandPlaylist.
    // Leave windowed playlists alone; VoicePlayer owns expanding them.
    if (isContiguousSliceOf(book, player, playlistSize)) {
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

  /**
   * True when the player's items are a contiguous mid-book range of [book]
   * (a [MediaItemProvider.playbackItemsWindow]), not the leading prefix this
   * synchronizer knows how to extend.
   */
  private fun isContiguousSliceOf(
    book: voice.core.data.Book,
    player: Player,
    playlistSize: Int,
  ): Boolean {
    if (playlistSize <= 0) return false
    val firstId = player.getMediaItemAt(0).mediaId
    val startIndex = mediaItemProvider.indexOfPlaybackItem(book, firstId) ?: return false
    if (startIndex == 0) return false
    val expected = mediaItemProvider.playbackItemIds(
      book = book,
      fromItemIndex = startIndex,
      limit = playlistSize,
    )
    if (expected.size != playlistSize) return false
    return expected.indices.all { index ->
      player.getMediaItemAt(index).mediaId == expected[index]
    }
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
