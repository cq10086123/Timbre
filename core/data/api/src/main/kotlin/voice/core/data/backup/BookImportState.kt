package voice.core.data.backup

import voice.core.data.BookId

/**
 * What the importer is working on right now.
 *
 * The importer stores a book as soon as its first chapter batch is analyzed,
 * so the book is on the shelf and playable while the rest is still being
 * scanned. A consumer that writes the shelf somewhere else (the WebDAV
 * backup) must not save such a book as if it were complete.
 */
public interface BookImportState {
  /**
   * The books being imported at this moment, with the chapter total the
   * importer found for each. A total of 0 means the folder listing is still
   * running and the chapter count is not known yet.
   */
  public fun chapterTotals(): Map<BookId, Int>
}
