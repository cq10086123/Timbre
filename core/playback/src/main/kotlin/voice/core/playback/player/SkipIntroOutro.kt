package voice.core.playback.player

import androidx.media3.common.C
import androidx.media3.common.MediaItem
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
  private var pendingIntroSkip = 0L

  fun attachTo(player: Player) {
    this.player = player
    player.addListener(this)
    scope.launch {
      contentRepo.flow().collectLatest { contents ->
        val bookId = player.currentBookId() ?: return@collectLatest
        val content = contents.firstOrNull { it.id == bookId } ?: return@collectLatest
        skipIntroMs = content.skipIntro.coerceAtLeast(0L)
        skipOutroMs = content.skipOutro.coerceAtLeast(0L)
      }
    }
    startTicking()
  }

  /**
   * Only the chapter that is entered gets its intro skipped. A seek back into
   * the intro by the user is left alone, so this doesn't fight the auto rewind
   * after a pause.
   */
  override fun onMediaItemTransition(
    mediaItem: MediaItem?,
    reason: Int,
  ) {
    pendingIntroSkip = skipIntroMs
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

  private fun skipIntroIfPending(player: Player) {
    val intro = pendingIntroSkip
    if (intro <= 0L) return
    pendingIntroSkip = 0L
    val duration = player.duration.takeUnless { it == C.TIME_UNSET } ?: return
    if (duration <= intro) return
    val position = player.currentPosition.takeUnless { it == C.TIME_UNSET } ?: return
    if (position >= intro) return
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
