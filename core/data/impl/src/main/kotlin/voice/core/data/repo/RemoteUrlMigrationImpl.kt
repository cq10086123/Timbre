package voice.core.data.repo

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import voice.core.data.repo.internals.transaction

/**
 * Performs the address-change migration against the same Room database used by
 * the repositories. The rows are collected before any primary key is changed,
 * which keeps cursor invalidation from losing a row half way through a move.
 */
@ContributesBinding(AppScope::class)
public class RemoteUrlMigrationImpl(
  private val appDb: RoomDatabase,
  private val bookRepository: BookRepository,
) : RemoteUrlMigration {

  private val json = Json

  override suspend fun migrate(
    oldPrefix: String,
    newPrefix: String,
  ) {
    val old = normalizePrefix(oldPrefix)
    val new = normalizePrefix(newPrefix)
    require(isHttpPrefix(old) && isHttpPrefix(new)) {
      "Remote URL migration requires http(s) prefixes"
    }
    if (old == new) return

    appDb.transaction {
      val db = appDb.openHelper.writableDatabase
      rewriteBookContent(db, old, new)
      rewriteChapters(db, old, new)
      rewriteBookmarks(db, old, new)
    }

    // Room has no invalidation signal for a raw primary-key rewrite. Refresh
    // every repository cache only after the transaction committed, otherwise a
    // failed migration could publish a half-migrated library.
    bookRepository.invalidateCaches()
  }

  private fun rewriteBookContent(
    db: SupportSQLiteDatabase,
    old: String,
    new: String,
  ) {
    data class Row(
      val rowId: Long,
      val id: String,
      val chapters: String,
      val currentChapter: String,
    )

    val rows = db.query("SELECT rowid, id, chapters, currentChapter FROM content2").use { cursor ->
      buildList {
        while (cursor.moveToNext()) {
          add(
            Row(
              rowId = cursor.getLong(cursor.getColumnIndexOrThrow("rowid")),
              id = cursor.getString(cursor.getColumnIndexOrThrow("id")),
              chapters = cursor.getString(cursor.getColumnIndexOrThrow("chapters")),
              currentChapter = cursor.getString(cursor.getColumnIndexOrThrow("currentChapter")),
            ),
          )
        }
      }
    }

    rows.forEach { row ->
      val id = rewrite(row.id, old, new)
      val chapters = rewriteChapterList(row.chapters, old, new)
      val currentChapter = rewrite(row.currentChapter, old, new)
      if (id == row.id && chapters == row.chapters && currentChapter == row.currentChapter) return@forEach

      val values = ContentValues().apply {
        put("id", id)
        put("chapters", chapters)
        put("currentChapter", currentChapter)
      }
      db.update(
        "content2",
        SQLiteDatabase.CONFLICT_ABORT,
        values,
        "rowid = ?",
        arrayOf(row.rowId.toString()),
      )
    }
  }

  private fun rewriteChapters(
    db: SupportSQLiteDatabase,
    old: String,
    new: String,
  ) {
    val ids = db.query("SELECT id FROM chapters2").use { cursor ->
      buildList {
        val index = cursor.getColumnIndexOrThrow("id")
        while (cursor.moveToNext()) add(cursor.getString(index))
      }
    }
    ids.forEach { id ->
      val rewritten = rewrite(id, old, new)
      if (rewritten == id) return@forEach
      val values = ContentValues().apply { put("id", rewritten) }
      db.update(
        "chapters2",
        SQLiteDatabase.CONFLICT_ABORT,
        values,
        "id = ?",
        arrayOf(id),
      )
    }
  }

  private fun rewriteBookmarks(
    db: SupportSQLiteDatabase,
    old: String,
    new: String,
  ) {
    data class Row(
      val rowId: Long,
      val bookId: String,
      val chapterId: String,
    )

    val rows = db.query("SELECT rowid, bookId, chapterId FROM bookmark2").use { cursor ->
      buildList {
        val rowIdIndex = cursor.getColumnIndexOrThrow("rowid")
        val bookIdIndex = cursor.getColumnIndexOrThrow("bookId")
        val chapterIdIndex = cursor.getColumnIndexOrThrow("chapterId")
        while (cursor.moveToNext()) {
          add(
            Row(
              rowId = cursor.getLong(rowIdIndex),
              bookId = cursor.getString(bookIdIndex),
              chapterId = cursor.getString(chapterIdIndex),
            ),
          )
        }
      }
    }
    rows.forEach { row ->
      val bookId = rewrite(row.bookId, old, new)
      val chapterId = rewrite(row.chapterId, old, new)
      if (bookId == row.bookId && chapterId == row.chapterId) return@forEach
      val values = ContentValues().apply {
        put("bookId", bookId)
        put("chapterId", chapterId)
      }
      db.update(
        "bookmark2",
        SQLiteDatabase.CONFLICT_ABORT,
        values,
        "rowid = ?",
        arrayOf(row.rowId.toString()),
      )
    }
  }

  private fun rewriteChapterList(
    value: String,
    old: String,
    new: String,
  ): String {
    val chapterIds = runCatching {
      json.decodeFromString(ListSerializer(String.serializer()), value)
    }.getOrNull() ?: return value
    val rewritten = chapterIds.map { rewrite(it, old, new) }
    if (rewritten == chapterIds) return value
    return json.encodeToString(ListSerializer(String.serializer()), rewritten)
  }

  private fun rewrite(
    value: String,
    old: String,
    new: String,
  ): String {
    if (!isHttpPrefix(value)) return value
    return when {
      value == old -> new
      value.startsWith("$old/") -> new + value.removePrefix(old)
      else -> value
    }
  }

  private fun normalizePrefix(value: String): String = value.trim().trimEnd('/')

  private fun isHttpPrefix(value: String): Boolean {
    return value.startsWith("http://", ignoreCase = true) ||
      value.startsWith("https://", ignoreCase = true)
  }
}
