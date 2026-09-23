package voice.core.online

import androidx.datastore.core.DataStore
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class OnlineSourceServiceTest {

  private val booksStore = FakeBooksStore()
  private val service = OnlineSourceService(
    FakeStore(false),
    FakeStore(""),
    FakeStore(""),
    FakeStore(""),
    booksStore,
    mockk(),
  )

  @Test
  fun `re-adding keeps position skip settings and measured durations`() = runTest {
    service.addToShelf(
      OnlineBook(
        source = "A",
        bookId = "b1",
        title = "T",
        currentChapterId = "c2",
        positionMs = 42_000L,
        skipIntroMs = 5_000L,
        skipOutroMs = 3_000L,
        chapters = listOf(
          OnlineChapter(id = "c1", title = "第1集", durationSeconds = 1800),
          OnlineChapter(id = "c2", title = "第2集", durationSeconds = 1750),
        ),
      ),
    )

    // the search dialog hands over a fresh copy without any local state
    service.addToShelf(
      OnlineBook(
        source = "A",
        bookId = "b1",
        title = "T",
        chapters = listOf(
          OnlineChapter(id = "c1", title = "第1集", durationSeconds = 0),
          OnlineChapter(id = "c2", title = "第2集", durationSeconds = 0),
          OnlineChapter(id = "c3", title = "第3集", durationSeconds = 1700),
        ),
      ),
    )

    val stored = booksStore.data.first().single()
    assertEquals("c2", stored.currentChapterId)
    assertEquals(42_000L, stored.positionMs)
    assertEquals(5_000L, stored.skipIntroMs)
    assertEquals(3_000L, stored.skipOutroMs)
    assertEquals(3, stored.chapters.size)
    assertEquals(1800, stored.chapters.single { it.id == "c1" }.durationSeconds)
    assertEquals(1700, stored.chapters.single { it.id == "c3" }.durationSeconds)
  }

  private class FakeStore<T>(initial: T) : DataStore<T> {
    private val state = MutableStateFlow(initial)
    override val data: Flow<T> = state
    override suspend fun updateData(transform: suspend (T) -> T): T {
      val next = transform(state.value)
      state.value = next
      return next
    }
  }

  private class FakeBooksStore : DataStore<List<OnlineBook>> {
    private val state = MutableStateFlow(emptyList<OnlineBook>())
    override val data: Flow<List<OnlineBook>> = state
    override suspend fun updateData(transform: suspend (List<OnlineBook>) -> List<OnlineBook>): List<OnlineBook> {
      val next = transform(state.value)
      state.value = next
      return next
    }
  }
}
