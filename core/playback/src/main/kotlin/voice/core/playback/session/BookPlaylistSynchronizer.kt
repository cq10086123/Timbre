package voice.core.playback.session

import androidx.media3.common.MediaItem
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
      return
    }
    val book = bookRepository.get(content.id) ?: return
    val itemCount = book.chapters.sumOf { it.chapterMarks.size }
    if (itemCount == playlistSize) {
      syncedChapters = book.content.chapters
      return
    }
    val items = mediaItemProvider.playbackItems(book)
    if (items.size > playlistSize && isPrefix(player, items, playlistSize)) {
      val playlistRanOut = player.playbackState == Player.STATE_ENDED && player.playWhenReady
      player.addMediaItems(playlistSize, items.subList(playlistSize, items.size))
      syncedChapters = book.content.chapters
      Logger.i("appended ${items.size - playlistSize} chapters of ${book.id} while playing")
      if (playlistRanOut) {
        // the import wasn't done when the last known chapter ended
        player.seekTo(playlistSize, 0)
        player.play()
      }
      return
    }

    // the chapters changed in a way that can't be appended. Keep the current
    // chapter and its position, otherwise the playback would restart.
    val currentMediaId = player.currentMediaItem?.mediaId ?: return
    val currentIndex = items.indexOfFirst { it.mediaId == currentMediaId }
    if (currentIndex == -1) {
      Logger.w("Could not synchronize the playlist of ${book.id}")
      return
    }
    player.setMediaItems(items, currentIndex, player.currentPosition)
    syncedChapters = book.content.chapters
  }

  private fun isPrefix(
    player: Player,
    items: List<MediaItem>,
    count: Int,
  ): Boolean {
    for (index in 0 until count) {
      if (player.getMediaItemAt(index).mediaId != items[index].mediaId) {
        return false
      }
    }
    return true
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
