package voice.core.playback

import android.content.ComponentName
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.guava.asDeferred
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import voice.core.data.Book
import voice.core.data.BookId
import voice.core.data.ChapterId
import voice.core.data.repo.BookContentRepo
import voice.core.data.store.CurrentBookStore
import voice.core.logging.api.Logger
import voice.core.online.OnlinePlaybackCatalog
import voice.core.playback.misc.Decibel
import voice.core.playback.session.CustomCommand
import voice.core.playback.session.MediaId
import voice.core.playback.session.MediaItemProvider
import voice.core.playback.session.PlaybackService
import voice.core.playback.session.bookId
import voice.core.playback.session.playbackPositionFor
import voice.core.playback.session.sendCustomCommand
import voice.core.playback.session.toMediaIdOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * The app shares a single media controller: every additional connection is
 * pushed the whole playlist on every change, which for a book with hundreds of
 * chapters is a lot of duplicated work.
 */
@SingleIn(AppScope::class)
@Inject
class PlayerController(
  private val context: Context,
  @CurrentBookStore
  private val currentBookStoreId: DataStore<BookId?>,
  private val contentRepo: BookContentRepo,
  private val mediaItemProvider: MediaItemProvider,
  private val onlinePlaybackCatalog: OnlinePlaybackCatalog,
) {

  private var _controller: Deferred<MediaController> = newControllerAsync()

  private fun newControllerAsync() = MediaController
    .Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java)))
    .buildAsync()
    .asDeferred()

  private val controller: Deferred<MediaController>
    get() {
      if (_controller.isCompleted) {
        val completedController = _controller.getCompleted()
        if (!completedController.isConnected) {
          completedController.release()
          _controller = newControllerAsync()
        }
      }
      return _controller
    }
  private val scope = CoroutineScope(Dispatchers.Main.immediate)

  /**
   * Seeks to [positionInChapterMs] of [chapterId]. The caller passes the book
   * it already holds; loading and assembling it again here would delay the
   * seek of a big book by the time a whole assembly takes.
   */
  fun setPosition(
    book: Book,
    chapterId: ChapterId,
    positionInChapterMs: Long,
  ) = executeAfterPrepare { controller ->
    val position = book.playbackPositionFor(
      chapterId = chapterId,
      positionInChapterMs = positionInChapterMs,
    ) ?: return@executeAfterPrepare
    awaitAssembledPlaylist(controller, book.id, position.index)
    controller.seekTo(position.index, position.positionInMediaItemMs)
  }

  /**
   * A book that [maybePrepare] just loaded is set as its single book media
   * item; VoicePlayer replaces that placeholder with the per-chapter playlist
   * asynchronously. A seek issued before the playlist lands is silently
   * dropped - the controller only forwards seeks to indexes its own timeline
   * has, which right after prepare is the one-item placeholder - so the first
   * chapter picked in the player screen kept starting at the book's saved
   * chapter, and only the second pick, with the playlist in place, jumped.
   * Waiting here until the target item exists makes the first pick work too.
   */
  private suspend fun awaitAssembledPlaylist(
    controller: MediaController,
    bookId: BookId,
    itemIndex: Int,
  ) {
    if (playlistAssembled(controller.currentMediaItem?.mediaId, controller.mediaItemCount, bookId, itemIndex)) {
      return
    }
    val assembled = withTimeoutOrNull(PLAYLIST_ASSEMBLY_TIMEOUT_MS) {
      callbackFlow {
        val listener = object : Player.Listener {
          override fun onEvents(
            player: Player,
            events: Player.Events,
          ) {
            if (playlistAssembled(controller.currentMediaItem?.mediaId, controller.mediaItemCount, bookId, itemIndex)) {
              trySend(Unit)
            }
          }
        }
        controller.addListener(listener)
        // the playlist may have landed between the check above and the
        // listener registration
        if (playlistAssembled(controller.currentMediaItem?.mediaId, controller.mediaItemCount, bookId, itemIndex)) {
          trySend(Unit)
        }
        awaitClose { controller.removeListener(listener) }
      }.first()
    } != null
    if (!assembled) {
      // the book could not be assembled (deleted, or the source failed): the
      // seek below is the same lost cause it always was, but the wait is
      // worth logging because everything else points at a working player
      Logger.i("Playlist of $bookId was not assembled in time, seeking to item $itemIndex anyway")
    }
  }

  fun pauseIfCurrentBookDifferentFrom(id: BookId) {
    scope.launch {
      val controller = awaitConnect() ?: return@launch
      val currentBookId = controller.currentBookId()
      if (currentBookId != null && currentBookId != id) {
        controller.pause()
      }
    }
  }

  fun skipSilence(skip: Boolean) = executeAfterPrepare { controller ->
    controller.sendCustomCommand(CustomCommand.SetSkipSilence(skip))
  }

  fun fastForward() = executeAfterPrepare { controller ->
    controller.seekForward()
  }

  fun rewind() = executeAfterPrepare { controller ->
    controller.seekBack()
  }

  fun previous() = executeAfterPrepare { controller ->
    controller.sendCustomCommand(CustomCommand.ForceSeekToPrevious)
  }

  fun next() = executeAfterPrepare { controller ->
    controller.sendCustomCommand(CustomCommand.ForceSeekToNext)
  }

  fun play() = executeAfterPrepare { controller ->
    controller.play()
  }

  fun playPause() = executeAfterPrepare { controller ->
    if (controller.isPlaying) {
      controller.pause()
    } else {
      controller.play()
    }
  }

  private suspend fun maybePrepare(controller: MediaController): Boolean {
    val bookId = currentBookStoreId.data.first() ?: return false
    if (controller.currentBookId() == bookId &&
      controller.playbackState in listOf(Player.STATE_READY, Player.STATE_BUFFERING)
    ) {
      return true
    }
    // only the content is needed to start the book, resolving all of its
    // chapters here would delay the first playback of a big book
    val content = contentRepo.get(bookId)
      ?: onlinePlaybackCatalog.content(bookId)
      ?: return false
    controller.setMediaItem(mediaItemProvider.mediaItem(content))
    controller.prepare()
    return true
  }

  private fun MediaController.currentBookId(): BookId? {
    val currentMediaItem = currentMediaItem ?: return null
    val mediaId = currentMediaItem.mediaId.toMediaIdOrNull() ?: return null
    return mediaId.bookId
  }

  fun pauseWithRewind(rewind: Duration) = executeAfterPrepare { controller ->
    controller.pause()
    controller.seekBackBy(
      rewind = rewind,
      crossMediaItems = false,
    )
  }

  private fun MediaController.seekBackBy(
    rewind: Duration,
    crossMediaItems: Boolean,
  ) {
    var currentPosition = currentPosition.takeUnless { it == C.TIME_UNSET }
      ?.milliseconds
      ?: return
    var remaining = rewind
    var mediaItemIndex = currentMediaItemIndex.takeUnless { it == C.INDEX_UNSET } ?: return

    while (remaining > currentPosition) {
      if (!crossMediaItems) {
        seekTo(mediaItemIndex, 0)
        return
      }
      remaining -= currentPosition
      val previousMediaItemIndex = mediaItemIndex - 1
      if (previousMediaItemIndex < 0) {
        seekTo(0)
        return
      }
      currentPosition = getMediaItemAt(previousMediaItemIndex).mediaMetadata.durationMs?.milliseconds ?: return
      mediaItemIndex = previousMediaItemIndex
    }

    seekTo(mediaItemIndex, (currentPosition - remaining).inWholeMilliseconds)
  }

  fun setSpeed(speed: Float) = executeAfterPrepare { controller ->
    controller.setPlaybackSpeed(speed)
  }

  fun setGain(gain: Decibel) = executeAfterPrepare { controller ->
    controller.sendCustomCommand(CustomCommand.SetGain(gain))
  }

  fun setSkipIntro(skipIntroMs: Long) = executeAfterPrepare { controller ->
    controller.sendCustomCommand(CustomCommand.SetSkipIntro(skipIntroMs))
  }

  fun setSkipOutro(skipOutroMs: Long) = executeAfterPrepare { controller ->
    controller.sendCustomCommand(CustomCommand.SetSkipOutro(skipOutroMs))
  }

  fun setVolume(volume: Float) = executeAfterPrepare {
    require(volume in 0F..1F)
    it.volume = volume
  }

  suspend fun livePlaybackState(bookId: BookId? = null): LivePlaybackState? {
    val controller = awaitConnect() ?: return null
    return controller.livePlaybackStateSnapshot(bookId)
  }

  fun livePlaybackStateFlow(bookId: BookId? = null): Flow<LivePlaybackState?> = callbackFlow {
    val controller = awaitConnect()
    if (controller == null) {
      trySend(null)
      close()
      return@callbackFlow
    }

    fun emitSnapshot() {
      trySend(controller.livePlaybackStateSnapshot(bookId))
    }

    var tickJob: Job? = null
    fun updateTicking() {
      if (!controller.isPlaying) {
        tickJob?.cancel()
        return
      }
      if (tickJob?.isActive == true) {
        return
      }
      tickJob = launch {
        while (isActive) {
          delay(250.milliseconds)
          emitSnapshot()
        }
      }
    }

    val listener = object : Player.Listener {
      override fun onEvents(
        player: Player,
        events: Player.Events,
      ) {
        if (events.containsAny(
            Player.EVENT_PLAY_WHEN_READY_CHANGED,
            Player.EVENT_MEDIA_ITEM_TRANSITION,
            Player.EVENT_PLAYBACK_STATE_CHANGED,
          )
        ) {
          emitSnapshot()
          updateTicking()
        }
        if (events.containsAny(
            Player.EVENT_POSITION_DISCONTINUITY,
            Player.EVENT_PLAYBACK_PARAMETERS_CHANGED,
          )
        ) {
          emitSnapshot()
        }
      }
    }

    controller.addListener(listener)
    emitSnapshot()
    updateTicking()
    awaitClose {
      tickJob?.cancel()
      controller.removeListener(listener)
    }
  }

  private inline fun executeAfterPrepare(crossinline action: suspend (MediaController) -> Unit) {
    scope.launch {
      val controller = awaitConnect() ?: return@launch
      if (maybePrepare(controller)) {
        action(controller)
      }
    }
  }

  @IgnorableReturnValue
  suspend fun awaitConnect(): MediaController? {
    return try {
      controller.await()
    } catch (e: Exception) {
      if (e is CancellationException) currentCoroutineContext().ensureActive()
      Logger.w(e, "Error while connecting to media controller")
      null
    }
  }
}

/**
 * True once the controller plays the assembled per-chapter playlist of
 * [bookId] and the item at [itemIndex] exists. The placeholder that
 * [PlayerController.maybePrepare] sets while VoicePlayer is still assembling
 * carries a book media id, and the playlist of another book must not pass
 * either - both would drop a seek just like an empty playlist does.
 */
internal fun playlistAssembled(
  currentMediaId: String?,
  mediaItemCount: Int,
  bookId: BookId,
  itemIndex: Int,
): Boolean {
  if (mediaItemCount <= itemIndex) return false
  val mediaId = currentMediaId?.toMediaIdOrNull() ?: return false
  return when (mediaId) {
    is MediaId.Chapter, is MediaId.ChapterMark -> mediaId.bookId == bookId
    is MediaId.Book, MediaId.Recent, MediaId.Root -> false
  }
}

/**
 * How long a chapter pick waits for the playlist of a freshly prepared book
 * before it seeks anyway. Assembling a book is a database read plus one media
 * item per chapter; only a failing source comes anywhere near the limit.
 */
private const val PLAYLIST_ASSEMBLY_TIMEOUT_MS = 10_000L
