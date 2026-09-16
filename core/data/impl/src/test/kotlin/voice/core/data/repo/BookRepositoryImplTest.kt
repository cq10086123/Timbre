package voice.core.data.repo

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import voice.core.data.BookContent
import voice.core.data.BookId
import voice.core.data.Chapter
import voice.core.data.ChapterId
import voice.core.data.MarkData
import voice.core.data.repo.internals.AppDb
import java.io.Closeable
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class BookRepositoryImplTest {

  private fun test(test: suspend TestEnvironment.() -> Unit) {
    runTest {
      TestEnvironment().use { test(it) }
    }
  }

  @Test
  fun libraryFlowReusesTheBookWhileOnlyThePositionChanges() = test {
    val bookId = BookId("book")
    val chapterId = ChapterId("chapter")
    putBook(bookId, chapterId, positionInChapter = 1000)

    val initialBook = repo.flow().first().single()

    repo.updatePlaybackPosition(
      id = bookId,
      currentChapter = chapterId,
      positionInChapter = 60_000,
      persist = false,
    )

    // the shelf only shows the persisted position, so the expensive assembly is
    // not repeated for every position update of a playing book
    val bookAfterPositionUpdate = repo.flow().first().single()
    assertSame(initialBook, bookAfterPositionUpdate)

    // the playback screen still sees the live position
    val liveBook = repo.get(bookId)!!
    assertEquals(expected = 60_000, actual = liveBook.content.positionInChapter)
  }

  @Test
  fun libraryFlowReassemblesTheBookWhenTheChaptersChange() = test {
    val bookId = BookId("book")
    val chapterId = ChapterId("chapter")
    putBook(bookId, chapterId, positionInChapter = 1000)

    val initialBook = repo.flow().first().single()
    assertEquals(expected = 42_000, actual = initialBook.chapters.single().duration)

    chapterRepo.put(
      Chapter(
        id = chapterId,
        name = "Chapter",
        duration = 43_000,
        fileLastModified = Instant.ofEpochMilli(1),
        fileSize = 1,
        markData = listOf(MarkData(startMs = 0, name = "Chapter")),
      ),
    )

    val updatedBook = repo.flow().first().single()
    assertEquals(expected = 43_000, actual = updatedBook.chapters.single().duration)
    assertTrue(initialBook !== updatedBook)
  }

  private class TestEnvironment : Closeable {

    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDb::class.java)
      .allowMainThreadQueries()
      .build()
    private val contentRepo = BookContentRepoImpl(db.bookContentDao())
    val chapterRepo = ChapterRepoImpl(db.chapterDao())
    val repo = BookRepositoryImpl(chapterRepo, contentRepo)

    suspend fun putBook(
      bookId: BookId,
      chapterId: ChapterId,
      positionInChapter: Long,
    ) {
      chapterRepo.put(
        Chapter(
          id = chapterId,
          name = "Chapter",
          duration = 42_000,
          fileLastModified = Instant.ofEpochMilli(1),
          fileSize = 1,
          markData = listOf(MarkData(startMs = 0, name = "Chapter")),
        ),
      )
      contentRepo.put(
        BookContent(
          id = bookId,
          playbackSpeed = 1F,
          skipSilence = false,
          isActive = true,
          lastPlayedAt = Instant.ofEpochMilli(1),
          author = null,
          name = "Book",
          addedAt = Instant.ofEpochMilli(1),
          chapters = listOf(chapterId),
          currentChapter = chapterId,
          positionInChapter = positionInChapter,
          cover = null,
          gain = 1F,
          genre = null,
          narrator = null,
          series = null,
          part = null,
        ),
        persist = true,
      )
    }

    override fun close() {
      db.close()
    }
  }
}
