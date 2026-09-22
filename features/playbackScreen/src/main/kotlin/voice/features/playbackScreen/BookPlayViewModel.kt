package voice.features.playbackScreen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.datastore.core.DataStore
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import voice.core.common.DispatcherProvider
import voice.core.common.MainScope
import voice.core.data.Book
import voice.core.data.BookId
import voice.core.data.KioskModeDemoData
import voice.core.data.durationMs
import voice.core.data.markForPosition
import voice.core.data.repo.BookRepository
import voice.core.data.repo.BookmarkRepo
import voice.core.data.sleeptimer.SleepTimerPreference
import voice.core.data.store.CurrentBookStore
import voice.core.data.store.SleepTimerPreferenceStore
import voice.core.featureflag.ExperimentalPlaybackPersistenceQualifier
import voice.core.featureflag.FeatureFlag
import voice.core.featureflag.KioskModeFeatureFlagQualifier
import voice.core.logging.api.Logger
import voice.core.online.OnlinePlaybackCatalog
import voice.core.playback.CurrentBookResolver
import voice.core.playback.PlayerController
import voice.core.playback.misc.Decibel
import voice.core.playback.misc.VolumeGain
import voice.core.playback.overlay
import voice.core.playback.playstate.PlayStateManager
import voice.core.sleeptimer.SleepTimer
import voice.core.sleeptimer.SleepTimerMode
import voice.core.sleeptimer.SleepTimerMode.TimedWithDuration
import voice.core.sleeptimer.SleepTimerState
import voice.core.ui.formatTime
import voice.features.playbackScreen.batteryOptimization.BatteryOptimization
import voice.features.sleepTimer.SleepTimerViewState
import voice.navigation.Destination
import voice.navigation.Navigator
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

@AssistedInject
class BookPlayViewModel(
  private val bookRepository: BookRepository,
  private val currentBookResolver: CurrentBookResolver,
  private val player: PlayerController,
  private val sleepTimer: SleepTimer,
  private val playStateManager: PlayStateManager,
  private val onlinePlaybackCatalog: OnlinePlaybackCatalog,
  @CurrentBookStore
  private val currentBookStoreId: DataStore<BookId?>,
  private val navigator: Navigator,
  private val bookmarkRepository: BookmarkRepo,
  private val volumeGainFormatter: VolumeGainFormatter,
  private val batteryOptimization: BatteryOptimization,
  dispatcherProvider: DispatcherProvider,
  @SleepTimerPreferenceStore
  private val sleepTimerPreferenceStore: DataStore<SleepTimerPreference>,
  @ExperimentalPlaybackPersistenceQualifier
  private val experimentalPlaybackPersistenceFeatureFlag: FeatureFlag<Boolean>,
  @KioskModeFeatureFlagQualifier
  private val kioskModeFeatureFlag: FeatureFlag<Boolean>,
  @Assisted
  private val bookId: BookId,
) {

  private val scope = MainScope(dispatcherProvider)

  internal val viewEffects: Flow<BookPlayViewEffect>
    field = MutableSharedFlow<BookPlayViewEffect>(extraBufferCapacity = 1)

  internal val dialogState: State<BookPlayDialogViewState?>
    field = mutableStateOf<BookPlayDialogViewState?>(null)

  init {
    scope.launch {
      player.pauseIfCurrentBookDifferentFrom(bookId)
      currentBookStoreId.updateData { bookId }
    }
    scope.launch {
      onlinePlaybackCatalog.playbackErrors.collect { error ->
        if (error.bookUri == bookId.value) {
          viewEffects.tryEmit(BookPlayViewEffect.OnlineSourceError(error.kind))
        }
      }
    }
  }

  @Composable
  fun viewState(): BookPlayViewState? {
    val kioskMode = remember { kioskModeFeatureFlag.get() }
    if (kioskMode) return kioskModeViewState()

    if (onlinePlaybackCatalog.isOnlineBookId(bookId)) {
      return onlineViewState()
    }

    val persistedBook = remember(bookId) {
      bookRepository.flow(bookId).filterNotNull()
    }.collectAsState(initial = null).value ?: return null

    val experimentalPlaybackPersistence = experimentalPlaybackPersistenceFeatureFlag.get()
    val livePlaybackState = if (experimentalPlaybackPersistence) {
      remember(bookId) { player.livePlaybackStateFlow(bookId) }
        .collectAsState(null).value
    } else {
      null
    }
    val managerPlayState by remember {
      playStateManager.playStateFlow
    }.collectAsState()

    val book = if (livePlaybackState != null) {
      persistedBook.overlay(livePlaybackState)
    } else {
      persistedBook
    }
    val isPlaying = livePlaybackState?.isPlaying ?: (managerPlayState == PlayStateManager.PlayState.Playing)

    return bookPlayViewState(book = book, isPlaying = isPlaying)
  }

  /**
   * View state of an online book: it is not in room, so the book is fetched
   * from the online catalog once and the live player state overlays it on
   * every change (the live position is the only position an online book has).
   */
  @Composable
  private fun onlineViewState(): BookPlayViewState? {
    val durationsVersion = remember { onlinePlaybackCatalog.durationsVersion }
      .collectAsState().value
    var baseBook by remember(bookId) { mutableStateOf<Book?>(null) }
    LaunchedEffect(bookId, durationsVersion) {
      // the book may need a moment when it comes from the search stash; the
      // measured durations arrive right after the first stream open
      var attempts = 0
      while (baseBook == null && attempts < 10) {
        baseBook = onlinePlaybackCatalog.book(bookId)
        if (baseBook == null) delay(500)
        attempts++
      }
    }
    val resolvingBooks = remember { onlinePlaybackCatalog.resolvingBooks }
      .collectAsState().value
    val livePlaybackState = remember(bookId) {
      player.livePlaybackStateFlow(bookId)
    }.collectAsState(null).value
    val managerPlayState by remember {
      playStateManager.playStateFlow
    }.collectAsState()
    val buffering by remember {
      playStateManager.bufferingFlow
    }.collectAsState()

    val isPlaying = livePlaybackState?.isPlaying ?: (managerPlayState == PlayStateManager.PlayState.Playing)
    // the ring means "the player is waiting for audio": while this book is
    // being resolved, or while playback stalls on the stream. A pause is
    // neither, so it cannot light the ring up.
    val loading = rememberWaitingForAudio(
      resolving = bookId.value in resolvingBooks,
      stalled = isPlaying && buffering,
    )

    val book = baseBook ?: return null
    val overlaid = livePlaybackState?.let { book.overlay(it) } ?: book
    // the synthesized online book carries no local cover file; the remote
    // cover url from the shelf or search stash is shown instead
    var onlineCover by remember(bookId) { mutableStateOf<String?>(null) }
    LaunchedEffect(bookId) {
      onlineCover = onlinePlaybackCatalog.onlineCover(bookId)
    }

    return bookPlayViewState(book = overlaid, isPlaying = isPlaying, loading = loading, coverOverride = onlineCover)
  }

  /**
   * True while [resolving] runs and after playback has been [stalled] for a
   * moment. Short stalls - a seek, the first bytes of a chapter - stay
   * invisible, so the ring only reports a stream that really is not keeping up.
   */
  @Composable
  private fun rememberWaitingForAudio(
    resolving: Boolean,
    stalled: Boolean,
  ): Boolean {
    var waited: Boolean by remember { mutableStateOf(false) }
    LaunchedEffect(stalled) {
      waited = false
      if (stalled) {
        delay(WAITING_FOR_AUDIO_GRACE_MS)
        waited = true
      }
    }
    return resolving || waited
  }

  @Composable
  private fun bookPlayViewState(
    book: Book,
    isPlaying: Boolean,
    loading: Boolean = false,
    coverOverride: String? = null,
  ): BookPlayViewState {
    val currentMark = book.currentChapter.markForPosition(book.content.positionInChapter)
    val positionInCurrentMark = if (isPlaying && currentMark.durationMs > 0) {
      val relativePosition = book.content.positionInChapter - currentMark.startMs
      relativePosition.coerceIn(0L, currentMark.durationMs)
    } else {
      book.content.positionInChapter - currentMark.startMs
    }

    val sleepTime = remember { sleepTimer.state }.collectAsState().value
    val hasMoreThanOneChapter = book.chapters.sumOf { it.chapterMarks.count() } > 1
    return BookPlayViewState(
      sleepTimerState = sleepTime.toViewState(),
      playing = isPlaying,
      title = book.content.name,
      showPreviousNextButtons = hasMoreThanOneChapter,
      chapterName = currentMark.name.takeIf { hasMoreThanOneChapter },
      duration = currentMark.durationMs.milliseconds,
      playedTime = positionInCurrentMark.milliseconds,
      cover = coverOverride ?: book.content.coverUrl,
      skipSilence = book.content.skipSilence,
      loading = loading,
    )
  }

  private fun kioskModeViewState(): BookPlayViewState {
    val currentlyPlaying = KioskModeDemoData.currentlyPlaying
    val book = KioskModeDemoData.currentlyPlayingBook
    return BookPlayViewState(
      sleepTimerState = BookPlayViewState.SleepTimerViewState.Disabled,
      playing = true,
      title = currentlyPlaying.title,
      showPreviousNextButtons = true,
      chapterName = currentlyPlaying.chapter,
      duration = 14.hours + 27.minutes,
      playedTime = 10.hours + 24.minutes,
      cover = book.coverUrl,
      skipSilence = false,
    )
  }

  fun dismissDialog() {
    Logger.d("dismissDialog")
    dialogState.value = null
  }

  fun incrementSleepTime() {
    updateSleepTimeViewState {
      val customTime = it.customSleepTime
      val newTime = customTime + 1
      sleepTimerPreferenceStore.updateData { preference -> preference.copy(duration = newTime.minutes) }
      SleepTimerViewState(newTime)
    }
  }

  fun decrementSleepTime() {
    updateSleepTimeViewState {
      val customTime = it.customSleepTime
      val newTime = (customTime - 1).coerceAtLeast(1)
      sleepTimerPreferenceStore.updateData { preference ->
        preference.copy(duration = newTime.minutes)
      }
      SleepTimerViewState(newTime)
    }
  }

  fun onAcceptSleepTime(time: Int) {
    updateSleepTimeViewState {
      val book = currentBook() ?: return@updateSleepTimeViewState null
      scope.launch {
        bookmarkRepository.addBookmarkAtBookPosition(
          book = book,
          setBySleepTimer = true,
          title = null,
        )
      }
      sleepTimer.enable(TimedWithDuration(time.minutes))
      null
    }
  }

  fun onAcceptSleepAtEndOfChapter() {
    updateSleepTimeViewState {
      sleepTimer.enable(SleepTimerMode.EndOfChapter)
      null
    }
  }

  private fun updateSleepTimeViewState(update: suspend (SleepTimerViewState) -> SleepTimerViewState?) {
    scope.launch {
      val current = dialogState.value
      val updated: SleepTimerViewState? = if (current is BookPlayDialogViewState.SleepTimer) {
        update(current.viewState)
      } else {
        update(SleepTimerViewState(sleepTimerPreferenceStore.data.first().duration.inWholeMinutes.toInt()))
      }
      dialogState.value = updated?.let(BookPlayDialogViewState::SleepTimer)
    }
  }

  fun onPlaybackSpeedChanged(speed: Float) {
    dialogState.value = BookPlayDialogViewState.SpeedDialog(speed)
    player.setSpeed(speed)
  }

  fun onVolumeGainChanged(gain: Decibel) {
    dialogState.value = volumeGainDialogViewState(gain)
    player.setGain(gain)
  }

  fun onSkipIntroOutroIconClick() {
    scope.launch {
      val content = currentBook()?.content ?: return@launch
      dialogState.value = BookPlayDialogViewState.SkipDialog(
        skipIntroSeconds = content.skipIntro / MILLIS_PER_SECOND,
        skipOutroSeconds = content.skipOutro / MILLIS_PER_SECOND,
      )
    }
  }

  fun onSkipIntroChanged(seconds: Long) {
    val seconds = seconds.coerceAtLeast(0L)
    val state = dialogState.value as? BookPlayDialogViewState.SkipDialog ?: return
    dialogState.value = state.copy(skipIntroSeconds = seconds)
    player.setSkipIntro(seconds * MILLIS_PER_SECOND)
  }

  fun onSkipOutroChanged(seconds: Long) {
    val seconds = seconds.coerceAtLeast(0L)
    val state = dialogState.value as? BookPlayDialogViewState.SkipDialog ?: return
    dialogState.value = state.copy(skipOutroSeconds = seconds)
    player.setSkipOutro(seconds * MILLIS_PER_SECOND)
  }

  fun next() {
    player.next()
  }

  fun previous() {
    player.previous()
  }

  fun playPause() {
    if (playStateManager.playState != PlayStateManager.PlayState.Playing) {
      scope.launch {
        if (batteryOptimization.shouldRequest()) {
          viewEffects.tryEmit(BookPlayViewEffect.RequestIgnoreBatteryOptimization)
          batteryOptimization.onBatteryOptimizationsRequested()
        }
      }
    }
    player.playPause()
  }

  fun rewind() {
    player.rewind()
  }

  fun fastForward() {
    player.fastForward()
  }

  fun onCloseClick() {
    navigator.goBack()
  }

  fun onCurrentChapterClick() {
    scope.launch {
      val book = currentBook() ?: return@launch
      // the running totals keep this linear. Summing up all previous chapters
      // for every entry is too slow for books with hundreds of chapters.
      var previousMarks = 0
      var previousDuration = 0L
      val items = book.chapters.flatMap { chapter ->
        val firstMarkNumber = previousMarks + 1
        val chapterStart = previousDuration
        previousMarks += chapter.chapterMarks.count()
        previousDuration += chapter.duration
        chapter.chapterMarks.mapIndexed { markIndex, chapterMark ->
          BookPlayDialogViewState.SelectChapterDialog.ItemViewState(
            number = firstMarkNumber + markIndex,
            name = chapterMark.name ?: "",
            active = chapterMark == book.currentMark && chapter == book.currentChapter,
            time = formatTime(chapterStart + chapterMark.startMs),
          )
        }
      }
      dialogState.value = BookPlayDialogViewState.SelectChapterDialog(
        items = items,
        ranges = chapterRanges(
          itemCount = items.size,
          activeItemIndex = items.indexOfFirst { it.active },
        ),
      )
    }
  }

  fun onChapterClick(number: Int) {
    scope.launch {
      val book = currentBook() ?: return@launch
      var currentIndex = -1
      book.chapters.forEach { chapter ->
        chapter.chapterMarks.forEach { mark ->
          currentIndex++
          if (currentIndex == number - 1) {
            player.setPosition(
              book = book,
              chapterId = chapter.id,
              positionInChapterMs = mark.startMs,
            )
            dialogState.value = null
            return@launch
          }
        }
      }
    }
  }

  fun onPlaybackSpeedIconClick() {
    scope.launch {
      val playbackSpeed = currentBook()?.content?.playbackSpeed ?: return@launch
      dialogState.value = BookPlayDialogViewState.SpeedDialog(playbackSpeed)
    }
  }

  fun onVolumeGainIconClick() {
    scope.launch {
      val content = currentBook()?.content ?: return@launch
      dialogState.value = volumeGainDialogViewState(Decibel(content.gain))
    }
  }

  private fun volumeGainDialogViewState(gain: Decibel): BookPlayDialogViewState.VolumeGainDialog {
    return BookPlayDialogViewState.VolumeGainDialog(
      gain = gain,
      maxGain = VolumeGain.MAX_GAIN,
      valueFormatted = volumeGainFormatter.format(gain),
    )
  }

  fun onBookmarkClick() {
    navigator.goTo(Destination.Bookmarks(bookId))
  }

  fun onBookmarkLongClick() {
    scope.launch {
      val book = currentBook() ?: return@launch
      bookmarkRepository.addBookmarkAtBookPosition(
        book = book,
        title = null,
        setBySleepTimer = false,
      )
      viewEffects.tryEmit(BookPlayViewEffect.BookmarkAdded)
    }
  }

  fun seekTo(position: Duration) {
    scope.launch {
      val book = currentBook() ?: return@launch
      val currentChapter = book.currentChapter
      val currentMark = currentChapter.markForPosition(book.content.positionInChapter)
      player.setPosition(
        book = book,
        chapterId = currentChapter.id,
        positionInChapterMs = currentMark.startMs + position.inWholeMilliseconds,
      )
    }
  }

  fun toggleSleepTimer() {
    scope.launch {
      Logger.d("toggleSleepTimer while active=${sleepTimer.state.value}")
      if (sleepTimer.state.value.enabled) {
        sleepTimer.disable()
        dialogState.value = null
      } else {
        dialogState.value = BookPlayDialogViewState.SleepTimer(
          viewState = SleepTimerViewState(
            customSleepTime = sleepTimerPreferenceStore.data.first().duration.inWholeMinutes.toInt(),
          ),
        )
      }
    }
  }

  fun onBatteryOptimizationRequested() {
    navigator.goTo(Destination.BatteryOptimization)
  }

  fun toggleSkipSilence() {
    scope.launch {
      val skipSilence = currentBook()?.content?.skipSilence ?: return@launch
      player.skipSilence(!skipSilence)
    }
  }

  private suspend fun currentBook(): Book? {
    return currentBookResolver.book(bookId)
  }

  @AssistedFactory
  interface Factory {
    fun create(bookId: BookId): BookPlayViewModel
  }
}

private fun SleepTimerState.toViewState(): BookPlayViewState.SleepTimerViewState = when (this) {
  SleepTimerState.Disabled -> BookPlayViewState.SleepTimerViewState.Disabled
  is SleepTimerState.Enabled.WithDuration -> BookPlayViewState.SleepTimerViewState.Enabled.WithDuration(this.leftDuration)
  SleepTimerState.Enabled.WithEndOfChapter -> BookPlayViewState.SleepTimerViewState.Enabled.WithEndOfChapter
}

/**
 * How long playback may wait for the stream before the loading ring shows up.
 * Seeks and chapter starts resolve after a few hundred milliseconds; a source
 * that hangs keeps the ring on instead of looking like a pause.
 */
private const val WAITING_FOR_AUDIO_GRACE_MS = 700L
