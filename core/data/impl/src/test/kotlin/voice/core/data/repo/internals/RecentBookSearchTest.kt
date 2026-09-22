package voice.core.data.repo.internals

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import voice.core.data.repo.internals.dao.RecentBookSearchDao
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class RecentBookSearchTest {

  @Test
  fun `add delete replace`() = runTest {
    val db = Room.inMemoryDatabaseBuilder(
      ApplicationProvider.getApplicationContext(),
      AppDb::class.java,
    )
      .build()

    with(db.recentBookSearchDao()) {
      assertTrue(recentBookSearch().isEmpty())

      add("cats")
      assertEquals(expected = listOf("cats"), actual = recentBookSearch())

      add("dogs")
      assertEquals(expected = listOf("cats", "dogs"), actual = recentBookSearch())

      add("unicorns")
      assertEquals(expected = listOf("cats", "dogs", "unicorns"), actual = recentBookSearch())

      delete("dogs")
      assertEquals(expected = listOf("cats", "unicorns"), actual = recentBookSearch())

      add("cats")
      assertEquals(expected = listOf("unicorns", "cats"), actual = recentBookSearch())
    }
    db.close()
  }

  @Test
  fun `clear removes all searches and updates observers`() = runTest {
    val db = Room.inMemoryDatabaseBuilder(
      ApplicationProvider.getApplicationContext(),
      AppDb::class.java,
    ).build()
    try {
      val dao = db.recentBookSearchDao()
      dao.add("cats")
      dao.add("dogs")
      val history = dao.recentBookSearches().produceIn(backgroundScope)
      try {
        assertEquals(listOf("cats", "dogs"), history.receive())

        dao.clear()

        assertEquals(emptyList(), history.receive())
        assertEquals(emptyList(), dao.recentBookSearch())

        dao.add("unicorns")

        assertEquals(listOf("unicorns"), history.receive())
        assertEquals(listOf("unicorns"), dao.recentBookSearch())
      } finally {
        history.cancel()
      }
    } finally {
      db.close()
    }
  }

  @Test
  fun `clear is safe when history is already empty`() = runTest {
    val db = Room.inMemoryDatabaseBuilder(
      ApplicationProvider.getApplicationContext(),
      AppDb::class.java,
    ).build()
    try {
      val dao = db.recentBookSearchDao()

      dao.clear()
      dao.clear()

      assertEquals(emptyList(), dao.recentBookSearch())
    } finally {
      db.close()
    }
  }

  @Test
  fun `add over limit replaces`() = runTest {
    val db = Room.inMemoryDatabaseBuilder(
      ApplicationProvider.getApplicationContext(),
      AppDb::class.java,
    )
      .build()
    val dao = db.recentBookSearchDao()

    val terms = (0..30).map { it.toString() }
    terms.forEach {
      dao.add(it)
    }

    assertEquals(expected = terms.takeLast(RecentBookSearchDao.Companion.LIMIT), actual = dao.recentBookSearch())

    db.close()
  }
}
