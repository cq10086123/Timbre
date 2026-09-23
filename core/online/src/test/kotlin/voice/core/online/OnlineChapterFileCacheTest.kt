package voice.core.online

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OnlineChapterFileCacheTest {

  private val root = createTempDirectory("online-cache").toFile()
  private val cache = OnlineChapterFileCache(root)
  private val ref = OnlineChapterRef("A", "book/1", "ch:1")

  @Test
  fun `file names stay inside the root even with unsafe ids`() {
    val file = cache.fileFor(ref)

    assertTrue(file.absolutePath.startsWith(root.absolutePath))
    assertFalse(cache.isCached(ref))
  }

  @Test
  fun `only completed downloads count as cached`() {
    val tmp = cache.tmpFileFor(ref)
    tmp.parentFile?.mkdirs()
    tmp.writeBytes(ByteArray(16))
    assertFalse(cache.isCached(ref))

    assertTrue(cache.completeDownload(ref))
    assertTrue(cache.isCached(ref))
    assertEquals(1, cache.cachedFileCount("A", "book/1"))
    assertEquals(16L, cache.cachedBytes("A", "book/1"))
  }

  @Test
  fun `completing without a download fails`() {
    assertFalse(cache.completeDownload(ref))
  }

  @Test
  fun `clearBook drops chapters and partial downloads`() {
    val tmp = cache.tmpFileFor(ref)
    tmp.parentFile?.mkdirs()
    tmp.writeBytes(ByteArray(8))
    val _ = cache.completeDownload(ref)
    val other = cache.tmpFileFor(ref.copy(chapterId = "ch2"))
    other.parentFile?.mkdirs()
    other.writeBytes(ByteArray(8))

    assertTrue(cache.clearBook("A", "book/1"))
    assertFalse(cache.isCached(ref))
    assertEquals(0, cache.cachedFileCount("A", "book/1"))
    assertFalse(cache.clearBook("A", "book/1"))
  }
}
