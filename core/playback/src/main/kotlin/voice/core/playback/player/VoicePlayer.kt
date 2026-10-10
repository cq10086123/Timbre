package voice.core.playback.player

import android.net.Uri
import androidx.core.net.toUri
import androidx.datastore.core.DataStore
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import voice.core.analytics.api.Analytics
import voice.core.common.DispatcherProvider
import voice.core.data.Book
import voice.core.data.BookContent
import voice.core.data.BookId
import voice.core.data.repo.BookRepository
import voice.core.data.store.AutoRewindAmountStore
import voice.core.data.store.BtSkipToChapterStore
import voice.core.data.store.CurrentBookStore
import voice.core.data.store.SeekTimeStore
import voice.core.logging.api.Logger
import voice.core.online.OnlinePlaybackCatalog
import voice.core.online.OnlinePreloadSettings
import voice.core.online.OnlinePreloadSettingsStore
import voice.core.online.OnlineSourceStreamingClient
import voice.core.playback.misc.Decibel
import voice.core.playback.misc.VolumeGain
import voice.core.playback.session.ImageFileProvider
import voice.core.playback.session.MediaId
import voice.core.playback.session.MediaItemProvider
import voice.core.playback.session.bookId
import voice.core.playback.session.playbackItemForPosition
import voice.core.playback.session.positionInMediaItem
import voice.core.playback.session.toMediaIdOrNull
import voice.core.sleeptimer.SleepTimer
import voice.core.sleeptimer.SleepTimerState
import java.io.File
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

@Inject
class VoicePlayer(
  private val player: Player,
  private val repo: BookRepository,
  @CurrentBookStore
  private val currentBookStoreId: DataStore<BookId?>,
  @SeekTimeStore
  private val seekTimeStore: DataStore<Int>,
  @BtSkipToChapterStore
  private val btSkipToChapterStore: DataStore<Boolean>,
  @AutoRewindAmountStore
  private val autoRewindAmountStore: DataStore<Int>,
  private val mediaItemProvider: MediaItemProvider,
  private val onlinePlaybackCatalog: OnlinePlaybackCatalog,
  @OnlinePreloadSettingsStore
  private val onlinePreloadSettingsStore: DataStore<OnlinePreloadSettings>,
  @OnlineSourceStreamingClient
  private val onlineStreamingClient: OkHttpClient,
  private val imageFileProvider: ImageFileProvider,
  private val scope: CoroutineScope,
  private val volumeGain: VolumeGain,
  private val sleepTimer: SleepTimer,
  private val analytics: Analytics,
  private val dispatcherProvider: DispatcherProvider = DispatcherProvider(),
) : ForwardingPlayer(player) {

  // 蓝牙/锁屏的上一集/下一集行为：true=切换章节，false=快退/快进。
  // 在 init 里收集一次缓存，避免在媒体按键回调里阻塞读 DataStore。
  @Volatile
  private var btSkipToChapter: Boolean = true

  init {
    scope.launch {
      btSkipToChapterStore.data.collect { btSkipToChapter = it }
    }
  }

  private val endOfChapterSleepTimerListener = object : Player.Listener {
    override fun onPositionDiscontinuity(
      oldPosition: Player.PositionInfo,
      newPosition: Player.PositionInfo,
      reason: Int,
    ) {
      if (reason == DISCONTINUITY_REASON_AUTO_TRANSITION) {
        pauseAndDisableSleepTimerIfEndOfChapter()
      }
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
      if (playbackState == STATE_ENDED) {
        pauseAndDisableSleepTimerIfEndOfChapter()
      }
    }

    private fun pauseAndDisableSleepTimerIfEndOfChapter() {
      if (sleepTimer.state.value !is SleepTimerState.Enabled.WithEndOfChapter) return
      Logger.v("Pausing due to EndOfChapter")
      sleepTimer.disable()
      player.pause()
    }
  }

  /**
   * The preload state machine of online books: resolves the next chapters'
   * stream urls (and measures their durations) only while the current chapter
   * really plays, paced by the user's preload settings. Online sources are
   * frequently rate limited, so nothing here loads before playback and every
   * request is spaced and capped.
   */
  private val onlinePlaybackPreloader = OnlinePlaybackPreloader(
    player = player,
    catalog = onlinePlaybackCatalog,
    settingsStore = onlinePreloadSettingsStore,
    httpClient = onlineStreamingClient,
    scope = scope,
    dispatcherProvider = dispatcherProvider,
  )

  init {
    player.addListener(endOfChapterSleepTimerListener)
    player.addListener(onlinePlaybackPreloader)
  }

  fun forceSeekToNext() {
    scope.launch {
      val nextMediaItemIndex = player.nextMediaItemIndex.takeUnless { it == C.INDEX_UNSET }
      if (nextMediaItemIndex != null) {
        player.seekTo(nextMediaItemIndex, 0)
      } else if (player.playWhenReady && player.playbackState != Player.STATE_ENDED) {
        // there is no next chapter yet because the import hasn't stored it,
        // and the listener is playing. Ending the current chapter right away
        // makes the tap take effect instead of doing nothing while the rest
        // of the chapter would play out. The seek lands inside the last
        // chapter mark so it doesn't pause, and once the synchronizer appends
        // the next imported chapter it resumes into it automatically.
        val duration = player.duration
        if (player.currentMediaItemIndex != C.INDEX_UNSET && duration != C.TIME_UNSET) {
          player.seekTo(player.currentMediaItemIndex, (duration - 1).coerceAtLeast(0))
        }
      }
    }
  }

  fun forceSeekToPrevious() {
    scope.launch {
      // this is the explicit "previous chapter" action of the app, so it always
      // changes the chapter. Restarting the current one when it just started
      // made the button appear broken while listening to an episode.
      val previousMediaItemIndex = player.previousMediaItemIndex.takeUnless { it == C.INDEX_UNSET }
      if (previousMediaItemIndex != null) {
        player.seekTo(previousMediaItemIndex, 0)
      } else {
        player.seekTo(0)
      }
    }
  }

  override fun getAvailableCommands(): Player.Commands {
    // On Android 13, the notification always shows the "skip to next" and "skip to previous"
    // actions.
    // However these are also used internally when seeking for example through a bluetooth headset
    // We use these and delegate them to fast forward / rewind.
    // The player however only advertises the seek to next and previous item in the case
    // that it's not the first or last track. Therefore we manually advertise that these
    // are available.
    return super.getAvailableCommands()
      .buildUpon()
      .addAll(
        COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
        COMMAND_SEEK_TO_PREVIOUS,
        COMMAND_SEEK_TO_NEXT,
        COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
      )
      .build()
  }

  override fun seekToPreviousMediaItem() {
    if (btSkipToChapter) {
      super.seekToPreviousMediaItem()
    } else {
      seekBack()
    }
  }

  override fun seekToNextMediaItem() {
    if (btSkipToChapter) {
      super.seekToNextMediaItem()
    } else {
      seekForward()
    }
  }

  override fun seekToPrevious() {
    if (btSkipToChapter) {
      super.seekToPrevious()
    } else {
      seekBack()
    }
  }

  override fun seekToNext() {
    if (btSkipToChapter) {
      super.seekToNext()
    } else {
      seekForward()
    }
  }

  override fun seekBack() {
    scope.launch {
      seekBackBy(seekTimeStore.data.first().seconds)
    }
  }

  private suspend fun seekBackBy(skipAmount: Duration) {
    seekBackBy(
      skipAmount = skipAmount,
      crossMediaItems = true,
    )
  }

  private suspend fun seekBackBy(
    skipAmount: Duration,
    crossMediaItems: Boolean,
  ) {
    var currentPosition = player.currentPosition.takeUnless { it == C.TIME_UNSET }
      ?.milliseconds
      ?.coerceAtLeast(ZERO)
      ?: return
    var remaining = skipAmount
    var mediaItemIndex = player.currentMediaItemIndex.takeUnless { it == C.INDEX_UNSET } ?: return

    while (remaining > currentPosition) {
      if (!crossMediaItems) {
        player.seekTo(mediaItemIndex, 0)
        return
      }
      remaining -= currentPosition
      val previousMediaItemIndex = mediaItemIndex - 1
      if (previousMediaItemIndex < 0) {
        player.seekTo(0)
        return
      }
      val previousMediaItem = player.getMediaItemAt(previousMediaItemIndex)
      currentPosition = previousMediaItem.mediaMetadata.durationMs?.milliseconds ?: return
      mediaItemIndex = previousMediaItemIndex
    }

    player.seekTo(mediaItemIndex, (currentPosition - remaining).inWholeMilliseconds)
  }

  override fun seekForward() {
    scope.launch {
      val skipAmount = seekTimeStore.data.first().seconds

      val currentPosition = player.currentPosition.takeUnless { it == C.TIME_UNSET }
        ?.milliseconds
        ?.coerceAtLeast(ZERO)
        ?: return@launch
      val newPosition = currentPosition + skipAmount

      val duration = player.duration.takeUnless { it == C.TIME_UNSET }
        ?.milliseconds
        ?: return@launch

      if (newPosition > duration) {
        val nextMediaItemIndex = nextMediaItemIndex.takeUnless { it == C.INDEX_UNSET }
          ?: return@launch
        player.seekTo(nextMediaItemIndex, (duration - newPosition).absoluteValue.inWholeMilliseconds)
      } else {
        player.seekTo(newPosition.inWholeMilliseconds)
      }
    }
  }

  override fun play() {
    playWhenReady = true
    // a prepare withheld for an online book runs once playback is requested
    runPrepareIfAllowed()
  }

  override fun setPlayWhenReady(playWhenReady: Boolean) {
    Logger.d("setPlayWhenReady=$playWhenReady")
    analytics.event(if (playWhenReady) "play" else "pause")

    if (playWhenReady) {
      updateLastPlayedAt()
      runPrepareIfAllowed()
    } else {
      val currentPosition = player.currentPosition.takeUnless { it == C.TIME_UNSET }?.milliseconds ?: ZERO
      if (currentPosition > ZERO) {
        scope.launch {
          seekBackBy(
            skipAmount = autoRewindAmountStore.data.first().seconds,
            crossMediaItems = false,
          )
        }
      }
    }
    super.setPlayWhenReady(playWhenReady)
  }

  override fun pause() {
    playWhenReady = false
  }

  private fun updateLastPlayedAt() {
    scope.launch {
      currentBookStoreId.data.first()?.let { bookId ->
        repo.updateBook(bookId) {
          val lastPlayedAt = Instant.now()
          Logger.v("Update ${it.name}: lastPlayedAt to $lastPlayedAt")
          it.copy(lastPlayedAt = lastPlayedAt)
        }
      }
    }
  }

  override fun getPlaybackState(): Int = when (val state = super.getPlaybackState()) {
    // redirect buffering to ready to prevent visual artifacts on seeking
    STATE_BUFFERING -> STATE_READY
    else -> state
  }

  override fun setMediaItem(
    mediaItem: MediaItem,
    startPositionMs: Long,
  ) {
    setBook(mediaItem)
  }

  private var setBookJob: Job? = null
  private var playlistExpandJob: Job? = null

  // True while the media item currently being assembled belongs to an online
  // book - the only book kind whose source api calls must wait for playback.
  @Volatile
  private var currentBookIsOnline: Boolean = false

  // prepare()/play() arriving while setBook is still assembling ran on an
  // empty playlist and did nothing. The flag re-applies them once the items
  // land; play() needs no flag because playWhenReady persists on the player.
  private var pendingPrepare = false

  override fun prepare() {
    pendingPrepare = true
    runPrepareIfAllowed()
  }

  /**
   * Runs a requested prepare - unless the current book is an online one and
   * nobody is playing: entering the player of a rate limited source must not
   * spend its api budget on a chapter nobody listens to (the first request of
   * the actually picked chapter would then fail). The withheld prepare runs
   * on play()/setPlayWhenReady(true). Local and WebDav books prepare right
   * away like before - their data is local or the user's own server, so
   * buffering while paused is free.
   */
  private fun runPrepareIfAllowed() {
    if (!pendingPrepare) return
    if (currentBookIsOnline && !player.playWhenReady) return
    pendingPrepare = false
    super.prepare()
  }

  override fun stop() {
    setBookJob?.cancel()
    playlistExpandJob?.cancel()
    pendingPrepare = false
    currentBookIsOnline = false
    super.stop()
  }

  override fun clearMediaItems() {
    setBookJob?.cancel()
    playlistExpandJob?.cancel()
    pendingPrepare = false
    currentBookIsOnline = false
    super.clearMediaItems()
  }

  override fun setMediaItem(
    mediaItem: MediaItem,
    resetPosition: Boolean,
  ) {
    setBook(mediaItem)
  }
  override fun setMediaItems(mediaItems: List<MediaItem>) {
    val first = mediaItems.firstOrNull() ?: return
    setBook(first)
  }

  override fun setMediaItems(
    mediaItems: List<MediaItem>,
    resetPosition: Boolean,
  ) {
    val first = mediaItems.firstOrNull() ?: return
    setBook(first)
  }

  override fun setMediaItem(mediaItem: MediaItem) {
    setBook(mediaItem)
  }

  override fun setMediaItems(
    mediaItems: List<MediaItem>,
    startIndex: Int,
    startPositionMs: Long,
  ) {
    val first = mediaItems.firstOrNull() ?: return
    setBook(first)
  }

  private fun setBook(mediaItem: MediaItem) {
    Logger.v("setBook(${mediaItem.mediaId})")
    val mediaId = mediaItem.mediaId.toMediaIdOrNull()
    if (mediaId !is MediaId.Book) {
      if (mediaId != null) {
        Logger.w("Unexpected mediaId=$mediaId")
      }
      return
    }
    // Assembling touches the database and builds one MediaItem per chapter:
    // both grow with the chapter count and must never run on the calling
    // (usually main) thread. Later calls win: an assembly still in flight is
    // cancelled so rapid book switches cannot apply out of order.
    setBookJob?.cancel()
    playlistExpandJob?.cancel()
    pendingPrepare = false
    currentBookIsOnline = onlinePlaybackCatalog.isOnlineBookId(mediaId.id)
    setBookJob = scope.launch {
      // a failed assembly (book deleted, position no longer resolvable) must
      // not re-prepare the previous book's stale playlist, so the trailing
      // prepare is gated on the items actually having been set
      var assembled = false
      var expand: PreparedBook? = null
      val assemblyDuration = measureTime {
        // one IO hop: load the book, build a leading prefix and resolve cover.
        val prepared = withContext(dispatcherProvider.io) {
          prepareBook(mediaId.id)
        } ?: return@measureTime
        player.setPlaybackSpeed(prepared.book.content.playbackSpeed)
        // apply directly instead of through setSkipSilenceEnabled: that
        // override persists the value, which would rewrite the shelf on
        // every prepare with the value just read from it
        if (player is ExoPlayer) {
          player.skipSilenceEnabled = prepared.book.content.skipSilence
        }
        volumeGain.gain = Decibel(prepared.book.content.gain)
        player.setMediaItems(
          prepared.mediaItems,
          prepared.startIndex,
          prepared.startPositionMs,
        )
        assembled = true
        if (prepared.needsFullPlaylist) {
          expand = prepared
        }
      }
      // prepare()/play() that arrived while the book was still assembling ran
      // on an empty playlist and did nothing: apply them now that the items
      // landed, otherwise tap-play on a cold book stays silent. For an online
      // book the prepare stays withheld while paused (runPrepareIfAllowed).
      if (assembled && (pendingPrepare || player.playWhenReady)) {
        pendingPrepare = true
        runPrepareIfAllowed()
      }
      // assembling the playlist of a book with thousands of chapters is the
      // one step of a playback start that grows with the chapter count. The
      // log marks how long it took so a slow start can be attributed.
      Logger.i("setBook(${mediaItem.mediaId}) took $assemblyDuration")
      val toExpand = expand ?: return@launch
      // append the tail off the critical path; indexes already match the final
      // playlist so chapter picks inside the prefix work immediately
      playlistExpandJob = scope.launch {
        expandPlaylist(toExpand)
      }
    }
  }

  /**
   * Loads [bookId] and builds a leading playlist prefix through the resume
   * chapter. Deliberately resolves no stream url here: for an online book
   * that request waits until playback (see [runPrepareIfAllowed]), the
   * preload state machine then keeps the next chapters warm.
   */
  private suspend fun prepareBook(bookId: BookId): PreparedBook? = coroutineScope {
    // books of the online source live in their own store: the room
    // lookup misses, the online catalog synthesizes the book instead
    val book = repo.get(bookId) ?: onlinePlaybackCatalog.book(bookId) ?: return@coroutineScope null
    val currentPlaybackItem = book.playbackItemForPosition(
      chapterId = book.content.currentChapter,
      positionInChapterMs = book.content.positionInChapter,
    ) ?: return@coroutineScope null

    val coverDeferred = if (onlinePlaybackCatalog.isOnlineBookId(book.id)) {
      async {
        onlinePlaybackCatalog.onlineCover(book.id)
          ?.takeIf { it.isNotBlank() }
          ?.toSessionArtworkUri()
      }
    } else {
      null
    }

    // leading prefix: indexes == final full playlist, so chapter seeks and the
    // import synchronizer stay correct while the tail is still missing
    val prefix = mediaItemProvider.playbackItemsPrefix(
      book = book,
      resumeItemIndex = currentPlaybackItem.index,
    )
    val onlineCover = coverDeferred?.await()
    val mediaItems = if (onlineCover == null) {
      prefix.items
    } else {
      // the online cover resolves to a local file (or the remote url while it
      // still downloads); it feeds the notification and Android Auto artwork
      prefix.items.map { item ->
        item.buildUpon()
          .setMediaMetadata(
            item.mediaMetadata.buildUpon().setArtworkUri(onlineCover).build(),
          )
          .build()
      }
    }

    PreparedBook(
      book = book,
      mediaItems = mediaItems,
      startIndex = prefix.resumeIndex,
      startPositionMs = currentPlaybackItem.positionInMediaItem(book.content.positionInChapter),
      needsFullPlaylist = prefix.isPartial,
    )
  }

  /**
   * Appends the remaining chapters after the leading prefix. Uses
   * [Player.addMediaItems] so the playing item and its buffer are not replaced
   * — critical for long-book listen feel.
   */
  private suspend fun expandPlaylist(prepared: PreparedBook) {
    val prefixSize = prepared.mediaItems.size
    val tail = withContext(dispatcherProvider.io) {
      val raw = mediaItemProvider.playbackItems(prepared.book, fromItemIndex = prefixSize)
      if (raw.isEmpty()) return@withContext emptyList()
      if (!onlinePlaybackCatalog.isOnlineBookId(prepared.book.id)) {
        return@withContext raw
      }
      val cover = onlinePlaybackCatalog.onlineCover(prepared.book.id)
        ?.takeIf { it.isNotBlank() }
        ?.toSessionArtworkUri()
        ?: return@withContext raw
      raw.map { item ->
        item.buildUpon()
          .setMediaMetadata(
            item.mediaMetadata.buildUpon().setArtworkUri(cover).build(),
          )
          .build()
      }
    }
    if (tail.isEmpty()) return
    // drop the expansion when the user already switched books
    val playingBookId = player.currentMediaItem?.mediaId?.toMediaIdOrNull()?.bookId
    if (playingBookId != null && playingBookId != prepared.book.id) {
      return
    }
    // import synchronizer or a previous expand may already have grown the list
    val already = player.mediaItemCount
    if (already >= prefixSize + tail.size) {
      return
    }
    val missing = if (already > prefixSize) {
      // something else appended a shorter tail; only add what is still missing
      tail.drop(already - prefixSize)
    } else {
      tail
    }
    if (missing.isEmpty()) return
    player.addMediaItems(already, missing)
    Logger.i(
      "Expanded playlist of ${prepared.book.id}: " +
        "prefix=$prefixSize +${missing.size} → ${already + missing.size}",
    )
  }

  /**
   * The media session artwork is loaded by the system (lock screen, wear,
   * auto): a local cover file must be handed out as a granted content:// uri,
   * a remote url passes through unchanged.
   */
  private fun String.toSessionArtworkUri(): Uri {
    val uri = toUri()
    if (uri.scheme != "file") return uri
    val file = uri.path?.let(::File) ?: return uri
    return imageFileProvider.uri(file)
  }

  override fun setPlaybackSpeed(speed: Float) {
    super.setPlaybackSpeed(speed)
    scope.launch {
      val bookId = currentBookStoreId.data.first()
      // online books have no room row: their values live on the online shelf
      if (bookId != null && onlinePlaybackCatalog.isOnlineBookId(bookId)) {
        onlinePlaybackCatalog.setPlaybackSpeed(bookId, speed)
      } else {
        updateBook { it.copy(playbackSpeed = speed) }
      }
    }
  }

  fun setSkipSilenceEnabled(enabled: Boolean) {
    scope.launch {
      val bookId = currentBookStoreId.data.first()
      // online books have no room row: their values live on the online shelf
      if (bookId != null && onlinePlaybackCatalog.isOnlineBookId(bookId)) {
        onlinePlaybackCatalog.setSkipSilence(bookId, enabled)
      } else {
        updateBook { it.copy(skipSilence = enabled) }
      }
    }
    if (player is ExoPlayer) {
      player.skipSilenceEnabled = enabled
    }
  }

  fun setGain(gain: Decibel) {
    volumeGain.gain = gain
    scope.launch {
      val bookId = currentBookStoreId.data.first()
      // online books have no room row: their values live on the online shelf
      if (bookId != null && onlinePlaybackCatalog.isOnlineBookId(bookId)) {
        onlinePlaybackCatalog.setGain(bookId, gain.value)
      } else {
        updateBook { it.copy(gain = gain.value) }
      }
    }
  }

  fun setSkipIntro(skipIntroMs: Long) {
    scope.launch {
      val value = skipIntroMs.coerceAtLeast(0)
      val bookId = currentBookStoreId.data.first()
      // online books have no room row: their values live on the online shelf
      if (bookId != null && onlinePlaybackCatalog.isOnlineBookId(bookId)) {
        onlinePlaybackCatalog.setSkipIntro(bookId, value)
      } else {
        updateBook { it.copy(skipIntro = value) }
      }
    }
  }

  fun setSkipOutro(skipOutroMs: Long) {
    scope.launch {
      val value = skipOutroMs.coerceAtLeast(0)
      val bookId = currentBookStoreId.data.first()
      // online books have no room row: their values live on the online shelf
      if (bookId != null && onlinePlaybackCatalog.isOnlineBookId(bookId)) {
        onlinePlaybackCatalog.setSkipOutro(bookId, value)
      } else {
        updateBook { it.copy(skipOutro = value) }
      }
    }
  }

  private suspend fun updateBook(update: (BookContent) -> BookContent) {
    val bookId = currentBookStoreId.data.first() ?: return
    repo.updateBook(bookId, update)
  }

  private data class PreparedBook(
    val book: Book,
    val mediaItems: List<MediaItem>,
    /**
     * Resume index inside [mediaItems]. For a leading prefix this is also the
     * final full-playlist index, so chapter seeks need not wait for the tail.
     */
    val startIndex: Int,
    val startPositionMs: Long,
    val needsFullPlaylist: Boolean,
  )
}
