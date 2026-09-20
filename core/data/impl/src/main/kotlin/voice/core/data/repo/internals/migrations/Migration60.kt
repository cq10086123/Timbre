package voice.core.data.repo.internals.migrations

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.binding
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import voice.core.data.normalizeRemoteUrl
import voice.core.data.repo.internals.getLong
import voice.core.data.repo.internals.getString
import voice.core.data.repo.internals.mapRows
import java.time.Instant

/**
 * Remote (webdav) urls are the ids of their books and chapters. Servers hand
 * out the same folder both with and without a trailing slash, so the ids of
 * already imported remote books are normalized to their canonical form.
 *
 * Without this migration a book that was imported as `https://nas/dav/Book/`
 * would look like a new book to a scan that derives `https://nas/dav/Book` for
 * the same folder: the stored book - with its listening progress and bookmarks
 * - would be deactivated (it disappears from the shelf) and re-imported from
 * scratch.
 */
@ContributesIntoSet(
  scope = AppScope::class,
  binding = binding<Migration>(),
)
public class Migration60 : IncrementalMigration(60) {

  private val json = Json

  override fun migrate(db: SupportSQLiteDatabase) {
    normalizeBooks(db)
    normalizeChapters(db)
    normalizeBookmarks(db)
  }

  private fun normalizeBooks(db: SupportSQLiteDatabase) {
    data class Row(
      val id: String,
      val chapters: String,
      val currentChapter: String,
      val lastPlayedAt: String,
    )

    // collect the rows before writing: writing to a table while its cursor is
    // still open would invalidate the cursor
    val rows = db.query("SELECT id, chapters, currentChapter, lastPlayedAt FROM content2").mapRows {
      Row(
        id = getString("id"),
        chapters = getString("chapters"),
        currentChapter = getString("currentChapter"),
        lastPlayedAt = getString("lastPlayedAt"),
      )
    }
    rows.forEach { row ->
      val id = normalizeRemoteUrl(row.id)
      val currentChapter = normalizeRemoteUrl(row.currentChapter)
      val chapters = normalizeChapterList(row.chapters)
      if (id == row.id && currentChapter == row.currentChapter && chapters == row.chapters) {
        return@forEach
      }
      val twin = db.query("SELECT id, lastPlayedAt FROM content2 WHERE id = ?", arrayOf(id)).mapRows {
        Row(
          id = getString("id"),
          chapters = "",
          currentChapter = "",
          lastPlayedAt = getString("lastPlayedAt"),
        )
      }.firstOrNull()
      if (twin != null && twin.id != row.id) {
        // two spellings of the same book collide on the canonical id. A blind
        // REPLACE would delete one of them with its listening progress, so the
        // most recently played twin wins and the other one is dropped.
        if (!isLaterOrEqual(row.lastPlayedAt, twin.lastPlayedAt)) {
          db.delete("content2", "id = ?", arrayOf(row.id))
          return@forEach
        }
        db.delete("content2", "id = ?", arrayOf(twin.id))
      }
      val values = ContentValues().apply {
        put("id", id)
        put("chapters", chapters)
        put("currentChapter", currentChapter)
      }
      db.update("content2", SQLiteDatabase.CONFLICT_REPLACE, values, "id = ?", arrayOf(row.id))
    }
  }

  /**
   * Normalizes the id of every chapter of a serialized chapter list. The list
   * is decoded as plain strings: `ChapterId` normalizes its ids in the
   * serializer already, so decoding it would hide the very trailing slashes
   * that have to be rewritten here.
   */
  private fun normalizeChapterList(chapters: String): String {
    val decoded = runCatching {
      json.decodeFromString(ListSerializer(String.serializer()), chapters)
    }.getOrNull() ?: return chapters
    var changed = false
    val normalized = decoded.map { chapter ->
      val value = normalizeRemoteUrl(chapter)
      if (value != chapter) {
        changed = true
      }
      value
    }
    if (!changed) return chapters
    return json.encodeToString(ListSerializer(String.serializer()), normalized)
  }

  private fun normalizeChapters(db: SupportSQLiteDatabase) {
    val ids = db.query("SELECT id FROM chapters2 WHERE id LIKE 'http://%' OR id LIKE 'https://%'").mapRows {
      getString("id")
    }
    ids.forEach { id ->
      val normalized = normalizeRemoteUrl(id)
      if (normalized == id) return@forEach
      val twin = db.query("SELECT id FROM chapters2 WHERE id = ?", arrayOf(normalized)).mapRows {
        getString("id")
      }.firstOrNull()
      if (twin != null && twin != id) {
        // the same chapter under two spellings: the canonical row survives,
        // the duplicate is dropped (its metadata is re-analyzed on the next scan)
        db.delete("chapters2", "id = ?", arrayOf(id))
        return@forEach
      }
      val values = ContentValues().apply { put("id", normalized) }
      db.update("chapters2", SQLiteDatabase.CONFLICT_REPLACE, values, "id = ?", arrayOf(id))
    }
  }

  private fun normalizeBookmarks(db: SupportSQLiteDatabase) {
    data class Row(
      val rowId: Long,
      val bookId: String,
      val chapterId: String,
    )

    val rows = db.query("SELECT rowid, bookId, chapterId FROM bookmark2").mapRows {
      Row(
        rowId = getLong("rowid"),
        bookId = getString("bookId"),
        chapterId = getString("chapterId"),
      )
    }
    rows.forEach { row ->
      val bookId = normalizeRemoteUrl(row.bookId)
      val chapterId = normalizeRemoteUrl(row.chapterId)
      if (bookId == row.bookId && chapterId == row.chapterId) return@forEach
      val values = ContentValues().apply {
        put("bookId", bookId)
        put("chapterId", chapterId)
      }
      db.update("bookmark2", SQLiteDatabase.CONFLICT_REPLACE, values, "rowid = ?", arrayOf(row.rowId.toString()))
    }
  }

  private fun isLaterOrEqual(
    first: String,
    second: String,
  ): Boolean {
    val firstInstant = runCatching { Instant.parse(first) }.getOrNull()
    val secondInstant = runCatching { Instant.parse(second) }.getOrNull()
    if (firstInstant != null && secondInstant != null) {
      return !firstInstant.isBefore(secondInstant)
    }
    return first >= second
  }
}
