package voice.core.data.repo

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import voice.core.data.BookContent
import voice.core.data.BookId
import voice.core.data.Bookmark
import voice.core.data.Chapter
import voice.core.data.ChapterId
import voice.core.data.repo.internals.AppDb
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class RemoteUrlMigrationTest {

  @Test
  fun addressChangeRewritesProgressChapterIdsAndBookmarksInOneDatabaseOperation() = runTest {
    val db = Room.inMemoryDatabaseBuilder(
      ApplicationProvider.getApplicationContext(),
      AppDb::class.java,
    ).allowMainThreadQueries().build()
    try {
      val oldRoot = "https://old.example.test/dav"
      val newRoot = "https://new.example.test/library"
      val oldBook = BookId("$oldRoot/book")
      val oldChapter = ChapterId("$oldRoot/book/01.mp3")
      val repo = BookRepositoryImpl(
        ChapterRepoImpl(db.chapterDao()),
        BookContentRepoImpl(db.bookContentDao()),
      )

      db.chapterDao().insert(
        Chapter(
          id = oldChapter,
          name = "Chapter 1",
          duration = 60_000,
          fileLastModified = Instant.EPOCH,
          fileSize = 123,
          markData = emptyList(),
        ),
      )
      db.bookContentDao().insert(
        BookContent(
          id = oldBook,
          playbackSpeed = 1F,
          skipSilence = false,
          isActive = true,
          lastPlayedAt = Instant.ofEpochSecond(10),
          author = "Author",
          name = "Book",
          addedAt = Instant.EPOCH,
          chapters = listOf(oldChapter),
          currentChapter = oldChapter,
          positionInChapter = 42_000,
          cover = null,
          gain = 0F,
          genre = null,
          narrator = null,
          series = null,
          part = null,
        ),
      )
      db.bookmarkDao().addBookmark(
        Bookmark(
          bookId = oldBook,
          chapterId = oldChapter,
          title = "Remember this",
          time = 12_345,
          addedAt = Instant.EPOCH,
          setBySleepTimer = false,
          id = Bookmark.Id(Uuid.random()),
        ),
      )

      RemoteUrlMigrationImpl(db, repo).migrate(oldRoot, newRoot)

      val newBook = repo.get(BookId("$newRoot/book"))
      assertNotNull(newBook)
      assertEquals(expected = 42_000, actual = newBook.content.positionInChapter)
      assertEquals(expected = "$newRoot/book/01.mp3", actual = newBook.content.currentChapter.value)
      assertEquals(expected = "$newRoot/book/01.mp3", actual = newBook.content.chapters.single().value)
      assertEquals(expected = 60_000, actual = newBook.chapters.single().duration)

      db.openHelper.readableDatabase.query(
        "SELECT bookId, chapterId, time FROM bookmark2",
      ).use { cursor ->
        assertEquals(expected = 1, actual = cursor.count)
        cursor.moveToFirst()
        assertEquals(expected = "$newRoot/book", actual = cursor.getString(0))
        assertEquals(expected = "$newRoot/book/01.mp3", actual = cursor.getString(1))
        assertEquals(expected = 12_345, actual = cursor.getLong(2))
      }
    } finally {
      db.close()
    }
  }
}
