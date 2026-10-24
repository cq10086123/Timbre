package voice.core.online

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OnlineShelfBackupContributorTest {

  private val service = mockk<OnlineSourceService>()
  private val coverDir = createTempDirectory("online-covers").toFile()
  private val coverStore = OnlineCoverStore(coverDir)
  private val contributor = OnlineShelfBackupContributor(service, coverStore)

  private fun book() = OnlineBook(
    source = "A",
    bookId = "42",
    title = "凡人修仙传",
    author = "忘语",
    addedAt = 1_700_000_000_000L,
    currentChapterId = "c2",
    positionMs = 4_200L,
    playbackSpeed = 1.25f,
    skipIntroMs = 15_000L,
    skipSilence = true,
  )

  @Test
  fun `save writes every book on the shelf`() = runTest {
    every { service.shelf() } returns flowOf(listOf(book()))

    val contribution = contributor.save()

    assertEquals(expected = 1, actual = contribution.count)
    val payload = contribution.payload.toString()
    assertTrue(payload.contains("凡人修仙传"))
    assertTrue(payload.contains("\"c2\""))
  }

  @Test
  fun `save keeps an empty shelf at zero books`() = runTest {
    every { service.shelf() } returns flowOf(emptyList())

    assertEquals(expected = 0, actual = contributor.save().count)
  }

  @Test
  fun `restore writes the backed up position back`() = runTest {
    every { service.shelf() } returns flowOf(listOf(book()))
    coEvery { service.restoreShelfEntry(any()) } returns Unit
    val payload = contributor.save().payload

    val result = contributor.restore(payload)

    assertEquals(expected = 1, actual = result.restored)
    assertEquals(expected = 1, actual = result.total)
    val restored = slot<OnlineBook>()
    coVerify { service.restoreShelfEntry(capture(restored)) }
    assertEquals(expected = "A::42", actual = restored.captured.key)
    assertEquals(expected = "c2", actual = restored.captured.currentChapterId)
    assertEquals(expected = 4_200L, actual = restored.captured.positionMs)
    assertEquals(expected = 1.25f, actual = restored.captured.playbackSpeed)
    assertEquals(expected = 15_000L, actual = restored.captured.skipIntroMs)
    assertTrue(restored.captured.skipSilence)
  }

  @Test
  fun `a payload of another section is left alone`() = runTest {
    val result = contributor.restore(JsonPrimitive("not a list"))

    assertEquals(expected = 0, actual = result.restored)
    assertEquals(expected = 0, actual = result.total)
  }

  @Test
  fun `the cover image travels with the backup`() = runTest {
    every { service.shelf() } returns flowOf(listOf(book()))
    coEvery { service.restoreShelfEntry(any()) } returns Unit
    checkNotNull(coverStore.write("A::42", byteArrayOf(1, 2, 3)))
    val payload = contributor.save().payload

    // another device: an empty cover directory of its own
    val otherDevice = OnlineShelfBackupContributor(
      service,
      OnlineCoverStore(createTempDirectory("restored-covers").toFile()),
    )
    val result = otherDevice.restore(payload)
    assertEquals(expected = 1, actual = result.restored)

    val restored = slot<OnlineBook>()
    coVerify { service.restoreShelfEntry(capture(restored)) }
    assertTrue(restored.captured.cover.startsWith("file:"))
    val file = File(java.net.URI(restored.captured.cover))
    assertTrue(file.isFile)
    assertEquals(listOf(1.toByte(), 2.toByte(), 3.toByte()), file.readBytes().toList())
  }

  @Test
  fun `a cover whose file is gone travels as its url`() = runTest {
    every { service.shelf() } returns flowOf(
      listOf(
        book().copy(
          cover = "file:/data/user/0/app.timbre.audiobook/files/online_covers/gone.img",
          coverUrl = "https://example.com/gone.jpg",
        ),
      ),
    )
    coEvery { service.restoreShelfEntry(any()) } returns Unit

    val payload = contributor.save().payload
    assertFalse(payload.toString().contains("file:/data"))

    val result = OnlineShelfBackupContributor(
      service,
      OnlineCoverStore(createTempDirectory("restored-url-covers").toFile()),
    ).restore(payload)
    assertEquals(expected = 1, actual = result.restored)

    val restored = slot<OnlineBook>()
    coVerify { service.restoreShelfEntry(capture(restored)) }
    assertEquals(expected = "https://example.com/gone.jpg", actual = restored.captured.cover)
    assertEquals(expected = "https://example.com/gone.jpg", actual = restored.captured.coverUrl)
  }

  @Test
  fun `a stale path without a url never reaches the backup`() = runTest {
    every { service.shelf() } returns flowOf(
      listOf(book().copy(cover = "file:/data/user/0/other-device/files/online_covers/gone.img")),
    )
    coEvery { service.restoreShelfEntry(any()) } returns Unit

    val payload = contributor.save().payload
    assertFalse(payload.toString().contains("file:/data"))

    val result = OnlineShelfBackupContributor(
      service,
      OnlineCoverStore(createTempDirectory("restored-blank-covers").toFile()),
    ).restore(payload)
    assertEquals(expected = 1, actual = result.restored)

    val restored = slot<OnlineBook>()
    coVerify { service.restoreShelfEntry(capture(restored)) }
    assertEquals(expected = "", actual = restored.captured.cover)
    assertNull(restored.captured.coverUrl)
  }

  @Test
  fun `restoring a backup from an older build drops its file path`() = runTest {
    coEvery { service.restoreShelfEntry(any()) } returns Unit
    // written before covers were embedded: the path of another device only
    val payload = Json.parseToJsonElement(
      """[{"source":"A","bookId":"42","title":"T","cover":"file:/data/user/0/other/files/x.img"}]""",
    )

    val result = contributor.restore(payload)
    assertEquals(expected = 1, actual = result.restored)

    val restored = slot<OnlineBook>()
    coVerify { service.restoreShelfEntry(capture(restored)) }
    assertEquals(expected = "", actual = restored.captured.cover)
  }
}
