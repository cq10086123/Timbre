package voice.core.data.repo.internals.internals.migrations

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider.getApplicationContext
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import voice.core.data.repo.internals.getInt
import voice.core.data.repo.internals.getString
import voice.core.data.repo.internals.mapRows
import voice.core.data.repo.internals.migrations.Migration60
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Two spellings of the same remote book (with and without a trailing slash)
 * must merge instead of one REPLACE-ing the other with its progress.
 */
@RunWith(AndroidJUnit4::class)
class Migration60CollisionTest {

  private lateinit var db: SupportSQLiteDatabase
  private lateinit var helper: SupportSQLiteOpenHelper

  @Before
  fun setUp() {
    val config = SupportSQLiteOpenHelper.Configuration
      .builder(getApplicationContext())
      .callback(
        object : SupportSQLiteOpenHelper.Callback(60) {
          override fun onCreate(db: SupportSQLiteDatabase) {
            db.execSQL(
              "CREATE TABLE content2 (" +
                "id TEXT NOT NULL PRIMARY KEY, " +
                "chapters TEXT NOT NULL, " +
                "currentChapter TEXT NOT NULL, " +
                "lastPlayedAt TEXT NOT NULL, " +
                "positionInChapter INTEGER NOT NULL)",
            )
            db.execSQL("CREATE TABLE chapters2 (id TEXT NOT NULL PRIMARY KEY)")
            db.execSQL("CREATE TABLE bookmark2 (bookId TEXT NOT NULL, chapterId TEXT NOT NULL)")
          }

          override fun onUpgrade(
            db: SupportSQLiteDatabase,
            oldVersion: Int,
            newVersion: Int,
          ) {
          }
        },
      )
      .build()
    helper = FrameworkSQLiteOpenHelperFactory().create(config)
    db = helper.writableDatabase
  }

  @After
  fun tearDown() {
    helper.close()
  }

  @Test
  fun collidingSpellingsMergeKeepingTheMostRecentlyPlayed() {
    val canonicalId = "https://nas.example.com/dav/Book"
    val slashedId = "https://nas.example.com/dav/Book/"
    val chapterId = "https://nas.example.com/dav/Book/01.mp3"
    val recent = "2024-06-01T12:00:00Z"

    insertContent(slashedId, position = 5, lastPlayedAt = Instant.EPOCH.toString(), chapterId)
    insertContent(canonicalId, position = 99, lastPlayedAt = recent, chapterId)
    insertChapter("$chapterId/")
    insertChapter(chapterId)
    insertBookmark(slashedId, "$chapterId/")

    Migration60().migrate(db)

    val books = db.query("SELECT id, positionInChapter, lastPlayedAt, chapters FROM content2").mapRows {
      Triple(getString("id"), getInt("positionInChapter"), getString("lastPlayedAt")) to getString("chapters")
    }
    assertEquals(
      expected = listOf(Triple(canonicalId, 99, recent) to """["$chapterId"]"""),
      actual = books,
    )
    val chapters = db.query("SELECT id FROM chapters2").mapRows { getString("id") }
    assertEquals(expected = listOf(chapterId), actual = chapters)
    val bookmarks = db.query("SELECT bookId, chapterId FROM bookmark2").mapRows {
      getString("bookId") to getString("chapterId")
    }
    assertEquals(expected = listOf(canonicalId to chapterId), actual = bookmarks)
  }

  @Test
  fun fresherSlashedTwinWins() {
    val canonicalId = "https://nas.example.com/dav/Book"
    val slashedId = "https://nas.example.com/dav/Book/"
    val chapterId = "https://nas.example.com/dav/Book/01.mp3"
    val recent = "2024-06-01T12:00:00Z"

    insertContent(canonicalId, position = 1, lastPlayedAt = Instant.EPOCH.toString(), chapterId)
    insertContent(slashedId, position = 7, lastPlayedAt = recent, chapterId)

    Migration60().migrate(db)

    val books = db.query("SELECT id, positionInChapter, lastPlayedAt FROM content2").mapRows {
      Triple(getString("id"), getInt("positionInChapter"), getString("lastPlayedAt"))
    }
    assertEquals(
      expected = listOf(Triple(canonicalId, 7, recent)),
      actual = books,
    )
  }

  private fun insertContent(
    id: String,
    position: Int,
    lastPlayedAt: String,
    chapterId: String,
  ) {
    db.insert(
      "content2",
      SQLiteDatabase.CONFLICT_FAIL,
      ContentValues().apply {
        put("id", id)
        put("chapters", """["$chapterId"]""")
        put("currentChapter", chapterId)
        put("lastPlayedAt", lastPlayedAt)
        put("positionInChapter", position)
      },
    )
  }

  private fun insertChapter(id: String) {
    db.insert(
      "chapters2",
      SQLiteDatabase.CONFLICT_FAIL,
      ContentValues().apply { put("id", id) },
    )
  }

  private fun insertBookmark(
    bookId: String,
    chapterId: String,
  ) {
    db.insert(
      "bookmark2",
      SQLiteDatabase.CONFLICT_FAIL,
      ContentValues().apply {
        put("bookId", bookId)
        put("chapterId", chapterId)
      },
    )
  }
}
