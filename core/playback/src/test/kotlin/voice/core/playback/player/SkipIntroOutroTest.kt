package voice.core.playback.player

import android.app.Application
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.test.utils.FakeMediaSource
import androidx.media3.test.utils.FakeTimeline
import androidx.media3.test.utils.TestExoPlayerBuilder
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper
import androidx.test.core.app.ApplicationProvider.getApplicationContext
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import voice.core.data.Book
import voice.core.data.BookContent
import voice.core.data.BookId
import voice.core.data.Chapter
import voice.core.data.ChapterId
import voice.core.data.MarkData
import voice.core.playback.session.MediaItemProvider
import voice.core.playback.session.realChapterId
import voice.core.playback.session.search.book
import voice.core.playback.session.toMediaIdOrNull
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class SkipIntroOutroTest {

  private val bookId = BookId(Uuid.random().toString())
  private val contentFlow = MutableStateFlow<List<BookContent>>(emptyList())

  private val currentChapters = mutableListOf<Chapter>()

  private val internalPlayer = TestExoPlayerBuilder(getApplicationContext<Application>())
    .setMediaSourceFactory(
      mockk {
        every { createMediaSource(any()) } answers {
          val mediaItem = arg<MediaItem>(0)
          val chapterId = mediaItem.mediaId.toMediaIdOrNull()!!.realChapterId
          val chapter = currentChapters.single { it.id == chapterId }
          FakeMediaSource(
            FakeTimeline(
              FakeTimeline.TimelineWindowDefinition.Builder()
                .setPeriodCount(1)
                .setSeekable(true)
                .setDurationUs(TimeUnit.MILLISECONDS.toMicros(chapter.duration))
                .setMediaItem(mediaItem)
                .build(),
            ),
          )
        }
      },
    )
    .build()

  private val scope = TestScope()

  private val skipIntroOutro = SkipIntroOutro(
    contentRepo = mockk {
      every { flow() } returns contentFlow
    },
    scope = scope,
  )

  init {
    skipIntroOutro.attachTo(internalPlayer)
  }

  @Test
  fun `intro is skipped when the content arrives after the items were set`() = scope.runTest {
    // a cold start: the playlist is assembled before the content (with the
    // skipIntro value) has arrived
    val chapters = listOf(chapter())
    setMediaItems(chapters)
    advanceTimeBy(2_000)

    contentFlow.value = listOf(contentOf(chapters, skipIntro = 5_000))
    runCurrent()
    internalPlayer.prepare()
    awaitReady()

    advanceTimeBy(2_000)
    assertEquals(expected = 5_000L, actual = internalPlayer.currentPosition)
  }

  @Test
  fun `a chapter without a resolved duration is not consumed`() = scope.runTest {
    val chapters = listOf(chapter())
    contentFlow.value = listOf(contentOf(chapters, skipIntro = 5_000))
    runCurrent()

    // the items are set but the player is idle: the duration is still unknown
    // and the tick must not burn the one attempt per item
    setMediaItems(chapters)
    advanceTimeBy(2_000)
    assertEquals(expected = 0L, actual = internalPlayer.currentPosition)

    internalPlayer.prepare()
    awaitReady()
    advanceTimeBy(2_000)
    assertEquals(expected = 5_000L, actual = internalPlayer.currentPosition)
  }

  @Test
  fun `a user seek back into the intro is left alone`() = scope.runTest {
    val chapters = listOf(chapter())
    contentFlow.value = listOf(contentOf(chapters, skipIntro = 5_000))
    runCurrent()
    setMediaItems(chapters)
    internalPlayer.prepare()
    awaitReady()

    advanceTimeBy(2_000)
    assertEquals(expected = 5_000L, actual = internalPlayer.currentPosition)

    internalPlayer.seekTo(1_000)
    advanceTimeBy(2_000)
    assertEquals(expected = 1_000L, actual = internalPlayer.currentPosition)
  }

  private fun contentOf(
    chapters: List<Chapter>,
    skipIntro: Long,
  ): BookContent {
    return book(chapters, bookId).content.copy(skipIntro = skipIntro)
  }

  private fun TestScope.setMediaItems(chapters: List<Chapter>) {
    currentChapters.clear()
    currentChapters += chapters
    val currentBook: Book = book(chapters, bookId)
    val mediaItemProvider = MediaItemProvider(mockk(), mockk(), mockk(), mockk(), mockk(), mockk())
    internalPlayer.setMediaItem(mediaItemProvider.mediaItem(currentBook))
    runCurrent()
  }

  private fun chapter(): Chapter {
    return Chapter(
      id = ChapterId(Uuid.random().toString()),
      name = "chapter",
      duration = 60_000L,
      fileLastModified = Instant.EPOCH,
      markData = listOf(MarkData(0L, "mark")),
      fileSize = 0,
    )
  }

  private fun awaitReady() {
    TestPlayerRunHelper.runUntilPlaybackState(internalPlayer, Player.STATE_READY)
  }
}
