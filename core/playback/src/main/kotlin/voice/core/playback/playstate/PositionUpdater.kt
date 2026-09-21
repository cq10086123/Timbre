package voice.core.playback.playstate

import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import voice.core.data.repo.BookRepository
import voice.core.featureflag.ExperimentalPlaybackPersistenceQualifier
import voice.core.featureflag.FeatureFlag
import voice.core.logging.api.Logger
import voice.core.online.OnlineDurationProbeAhead
import voice.core.online.OnlinePlaybackCatalog
import voice.core.playback.di.PlaybackScope
import voice.core.playback.session.bookId
import voice.core.playback.session.positionInChapter
import voice.core.playback.session.realChapterId
import voice.core.playback.session.toMediaIdOrNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

@Inject
@SingleIn(PlaybackScope::class)
class PositionUpdater(
  private val bookRepo: BookRepository,
  private val onlinePlaybackCatalog: OnlinePlaybackCatalog,
  private val durationProbeAhead: OnlineDurationProbeAhead,
  private val scope: CoroutineScope,
  private val playStateManager: PlayStateManager,
  @ExperimentalPlaybackPersistenceQualifier
  private val experimentalPlaybackPersistenceFeatureFlag: FeatureFlag<Boolean>,
) : Player.Listener {

  private var player: Player? = null
  private var updateJob: Job? = null
  private var lastPersistedAt: Long = 0L

  fun attachTo(player: Player) {
    this.player?.removeListener(this)
    this.player = player
    player.addListener(this)

    updateJob = scope.launch {
      playStateManager.playStateFlow
        .map { it == PlayStateManager.PlayState.Playing }
        .distinctUntilChanged()
        .collectLatest { playing ->
          if (playing) {
            while (true) {
              delay(
                if (experimentalPlaybackPersistenceFeatureFlag.get()) {
                  5.minutes
                } else {
                  400.milliseconds
                },
              )
              // the frequent updates only need to reach the ui. Writing them to
              // the database would rewrite the chapter list of the book twice a
              // second, which is very expensive for books with many chapters.
              flushPositionNow(force = false)
            }
          }
        }
    }
  }

  override fun onPositionDiscontinuity(
    oldPosition: Player.PositionInfo,
    newPosition: Player.PositionInfo,
    reason: Int,
  ) {
    val player = player ?: return
    // writing the content row of a big book is expensive, so it is only forced
    // when the chapter changed or while paused. While playing, the periodic
    // updater persists the position regularly anyway.
    val crossedChapter = oldPosition.mediaItemIndex != newPosition.mediaItemIndex
    flushPosition(crossedChapter || !player.playWhenReady)
  }

  override fun onPlayWhenReadyChanged(
    playWhenReady: Boolean,
    reason: Int,
  ) {
    flushPosition()
  }

  override fun onPlaybackStateChanged(playbackState: Int) {
    if (playbackState == Player.STATE_IDLE || playbackState == Player.STATE_ENDED) {
      flushPosition()
    }
  }

  override fun onMediaItemTransition(
    mediaItem: MediaItem?,
    reason: Int,
  ) {
    // a chapter change also reports a position discontinuity, which decides
    // whether the update has to be persisted
    flushPosition(force = false)
    // measure the next unknown chapters ahead of playback, so sources that
    // report no durations stop showing the placeholder one chapter at a time
    val mediaId = mediaItem?.mediaId?.toMediaIdOrNull()
    val bookId = mediaId?.bookId
    val chapterId = mediaId?.realChapterId
    if (bookId != null && chapterId != null) {
      durationProbeAhead.probeUpcoming(bookId, chapterId)
    }
  }

  private fun flushPosition(force: Boolean = true) {
    scope.launch {
      flushPositionNow(force)
    }
  }

  /**
   * Publishes the current position.
   *
   * @param force writes the position to the database. Without it the position is
   * written at most every [PERSIST_INTERVAL_MS] because the position in memory
   * is already enough to resume and to update the ui.
   */
  suspend fun flushPositionNow(force: Boolean = true) {
    val player = player ?: return
    val mediaItem = player.currentMediaItem ?: return
    val currentPosition = player.currentPosition
      .takeIf { it >= 0 } ?: return
    val mediaId = mediaItem.mediaId.toMediaIdOrNull() ?: return
    val bookId = mediaId.bookId ?: return
    val chapterId = mediaId.realChapterId ?: return
    val positionInChapter = mediaId.positionInChapter(currentPosition) ?: return
    val now = SystemClock.elapsedRealtime()
    val persist = force || now - lastPersistedAt >= PERSIST_INTERVAL_MS
    if (persist) {
      lastPersistedAt = now
    }
    Logger.d("$positionInChapter is the new position! (persist=$persist)")
    // online books have no room row: the catalog keeps their position itself
    // the player reports the real stream duration; the catalog persists it so
    // chapters without a source-reported length stop using placeholders
    onlinePlaybackCatalog.updatePosition(
      bookId = bookId,
      chapterId = chapterId,
      positionMs = positionInChapter,
      durationMs = player.duration.takeIf { it > 0 } ?: 0L,
    )
    bookRepo.updatePlaybackPosition(
      id = bookId,
      currentChapter = chapterId,
      positionInChapter = positionInChapter,
      persist = persist,
    )
  }

  fun release() {
    player?.removeListener(this)
    updateJob?.cancel()
  }
}

private const val PERSIST_INTERVAL_MS = 3_000L
