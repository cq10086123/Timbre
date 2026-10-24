package voice.core.webdav

import voice.core.data.BookId
import kotlin.test.Test
import kotlin.test.assertEquals

class BackupImportGuardTest {

  @Test
  fun `a book stored with only its first batch counts as importing`() {
    // the importer writes 20 of 160 chapters right away so the book can be
    // played during the import
    assertEquals(
      expected = 1,
      actual = unfinishedImports(
        stored = mapOf(BookId("book") to 20),
        pending = mapOf(BookId("book") to 160),
      ),
    )
  }

  @Test
  fun `a book that is not stored at all yet counts as importing`() {
    // between the folder listing and the first stored batch
    assertEquals(
      expected = 1,
      actual = unfinishedImports(stored = emptyMap(), pending = mapOf(BookId("book") to 0)),
    )
  }

  @Test
  fun `a refresh of a complete book does not block the backup`() {
    assertEquals(
      expected = 0,
      actual = unfinishedImports(
        stored = mapOf(BookId("book") to 160),
        pending = mapOf(BookId("book") to 160),
      ),
    )
  }

  @Test
  fun `an idle importer does not block the backup`() {
    assertEquals(
      expected = 0,
      actual = unfinishedImports(stored = mapOf(BookId("book") to 160), pending = emptyMap()),
    )
  }
}
