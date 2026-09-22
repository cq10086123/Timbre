package voice.core.playback.player

import androidx.media3.common.C
import androidx.media3.common.Player
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import voice.core.data.BookId
import voice.core.data.repo.BookContentRepo
import voice.core.playback.di.PlaybackScope
import voice.core.playback.session.bookId
import voice.core.playback.session.toMediaIdOrNull
import kotlin.time.Duration.Companion.milliseconds

/**
 * Skips the intro and the outro of every chapter of the current book.
 *
 * Both values are stored per book, so the settings of one book never leak into
 * another one. The values are read from the repository instead of being passed
 * in, which keeps them live: changing them in the player applies immediately.
 *
 * The intro is skipped once per media item: the item id marks what has been
 * handled, which survives the cases the previous "pending" flag lost:
 * - a cold start latched the initial 0 before the content (with the real
 *   skipIntro) arrived,
 * - the player had no duration yet when the tick ran, so the pending value was
 *   consumed without a skip ever happening.
 * The values are only used once the content of the book that owns the current
 * item was loaded, so a chapter is never skipped with the previous book's
 * value.
 */
@Inject
@SingleIn(PlaybackScope::class)
class SkipIntroOutro(
  private val contentRepo: BookContentRepo,
  private val scope: CoroutineScope,
) : Player.Listener {

  private var player: Player? = null
  private var tickJob: Job? = null
  private var skipIntroMs = 0L
  private var skipOutroMs = 0L

  /** The book whose content the values above were loaded from. */
  private var loadedForBookId: BookId? = null

  /** The media item whose intro skip has been fully handled (or found unnecessary). */
  private var introHandledItemId: String? = null

  fun attachTo(player: Player) {
    this.player = player
    player.addListener(this)
    scope.launch {
      contentRepo.flow().collectLatest { contents ->
        val bookId = player.currentBookId() ?: return@collectLatest
        val content = contents.firstOrNull { it.id == bookId } ?: return@collectLatest
        skipIntroMs = content.skipIntro.coerceAtLeast(0L)
        skipOutroMs = content.skipOutro.coerceAtLeast(0L)
        loadedForBookId = bookId
      }
    }
    startTicking()
  }

  private fun startTicking() {
    tickJob?.cancel()
    tickJob = scope.launch {
      while (isActive) {
        delay(TICK_INTERVAL)
        val player = this@SkipIntroOutro.player ?: continue
        skipIntroIfPending(player)
        skipOutroWhenReached(player)
      }
    }
  }

  /**
   * Only the chapter that is entered gets its intro skipped. A seek back into
   * the intro by the user is left alone: once an item was handled (skipped or
   * found unnecessary) it is never touched again, so this doesn't fight the
   * auto rewind after a pause.
   */
  private fun skipIntroIfPending(player: Player) {
    val mediaId = player.currentMediaItem?.mediaId ?: return
    if (mediaId == introHandledItemId) return
    val bookId = mediaId.toMediaIdOrNull()?.bookId
    if (bookId == null) {
      // not an app media id: nothing to skip, don't retry it forever
      introHandledItemId = mediaId
      return
    }
    if (bookId != loadedForBookId) {
      // the content of this book has not arrived yet: the next tick retries
      // instead of latching the previous book's value or the initial zero
      return
    }
    val intro = skipIntroMs
    if (intro <= 0L) {
      introHandledItemId = mediaId
      return
    }
    val duration = player.duration.takeUnless { it == C.TIME_UNSET }
      // the player has not resolved the chapter yet: retry on a later tick
      // instead of consuming the attempt without effect
      ?: return
    if (duration <= intro) {
      introHandledItemId = mediaId
      return
    }
    val position = player.currentPosition.takeUnless { it == C.TIME_UNSET }
      ?: return
    if (position >= intro) {
      // entered past the intro (resume or a user seek): nothing to skip
      introHandledItemId = mediaId
      return
    }
    introHandledItemId = mediaId
    player.seekTo(intro)
  }

  private fun skipOutroWhenReached(player: Player) {
    val outro = skipOutroMs
    if (outro <= 0L) return
    // while paused the outro is where the user left it, don't move on
    if (!player.isPlaying) return
    val duration = player.duration.takeUnless { it == C.TIME_UNSET } ?: return
    val position = player.currentPosition.takeUnless { it == C.TIME_UNSET } ?: return
    if (position < duration - outro) return
    val nextIndex = player.nextMediaItemIndex
    // the last chapter plays out, otherwise the book would just stop early
    if (nextIndex == C.INDEX_UNSET) return
    player.seekTo(nextIndex, 0L)
  }

  private fun Player.currentBookId(): BookId? {
    val mediaId = currentMediaItem?.mediaId ?: return null
    return mediaId.toMediaIdOrNull()?.bookId
  }
}

private val TICK_INTERVAL = 500.milliseconds
