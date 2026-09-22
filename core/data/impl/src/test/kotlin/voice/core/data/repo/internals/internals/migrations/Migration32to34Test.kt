package voice.core.data.repo.internals.internals.migrations

import android.annotation.SuppressLint
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.SupportSQLiteQueryBuilder
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider.getApplicationContext
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import voice.core.data.repo.internals.getLong
import voice.core.data.repo.internals.getString
import voice.core.data.repo.internals.mapRows
import voice.core.data.repo.internals.migrations.Migration32to34
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
class Migration32to34Test {

  private lateinit var db: SupportSQLiteDatabase
  private lateinit var helper: SupportSQLiteOpenHelper

  @Before
  fun setUp() {
    val config = SupportSQLiteOpenHelper.Configuration
      .builder(getApplicationContext())
      .callback(
        object : SupportSQLiteOpenHelper.Callback(32) {
          override fun onCreate(db: SupportSQLiteDatabase) {
            db.execSQL(BookmarkTable.CREATE_TABLE)
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
  fun bookmarksSurviveTheTableRecreation() {
    contentValuesForBookmark(path = "/sdcard/book/1.mp3", title = "start", time = 1_000L)
      .let { db.insert(BookmarkTable.TABLE_NAME, SQLiteDatabase.CONFLICT_FAIL, it) }
    contentValuesForBookmark(path = "/sdcard/book/2.mp3", title = "middle", time = 250_000L)
      .let { db.insert(BookmarkTable.TABLE_NAME, SQLiteDatabase.CONFLICT_FAIL, it) }

    Migration32to34().migrate(db)

    val query = SupportSQLiteQueryBuilder.builder(BookmarkTable.TABLE_NAME)
      .columns(
        arrayOf(
          BookmarkTable.PATH,
          BookmarkTable.TITLE,
          BookmarkTable.TIME,
        ),
      )
      .create()
    val bookmarks = db.query(query).mapRows {
      Holder(
        path = getString(BookmarkTable.PATH),
        title = getString(BookmarkTable.TITLE),
        time = getLong(BookmarkTable.TIME),
      )
    }
    assertEquals(
      expected = listOf(
        Holder("/sdcard/book/1.mp3", "start", 1_000L),
        Holder("/sdcard/book/2.mp3", "middle", 250_000L),
      ),
      actual = bookmarks,
    )
  }

  @SuppressLint("SdCardPath")
  private fun contentValuesForBookmark(
    path: String,
    title: String,
    time: Long,
  ) = ContentValues().apply {
    put(BookmarkTable.PATH, path)
    put(BookmarkTable.TITLE, title)
    put(BookmarkTable.TIME, time)
  }

  private data class Holder(
    val path: String,
    val title: String,
    val time: Long,
  )

  private object BookmarkTable {
    const val PATH = "bookmarkPath"
    const val TITLE = "bookmarkTitle"
    const val TIME = "bookmarkTime"
    const val TABLE_NAME = "tableBookmarks"
    const val CREATE_TABLE = """
      CREATE TABLE $TABLE_NAME (
        ${android.provider.BaseColumns._ID} INTEGER PRIMARY KEY AUTOINCREMENT,
        $PATH TEXT NOT NULL,
        $TITLE TEXT NOT NULL,
        $TIME INTEGER NOT NULL
      )
    """
  }
}
