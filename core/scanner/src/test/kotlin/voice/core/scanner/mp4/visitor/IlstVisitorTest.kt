package voice.core.scanner.mp4.visitor

import androidx.media3.common.util.ParsableByteArray
import voice.core.scanner.mp4.Mp4ChpaterExtractorOutput
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class IlstVisitorTest {

  @Test
  fun `reads the text tags`() {
    val output = Mp4ChpaterExtractorOutput()

    IlstVisitor().visit(
      buffer = ilst(
        textItem(type = TYPE_TITLE, text = "The Title"),
        textItem(type = TYPE_ARTIST, text = "The Artist"),
        textItem(type = TYPE_ALBUM, text = "The Album"),
        textItem(type = TYPE_GENRE, text = "The Genre"),
        textItem(type = TYPE_COMPOSER, text = "The Narrator"),
        // cover art is skipped without looking at its content
        item(type = TYPE_COVER, payload = byteArrayOf(1, 2, 3, 4, 5, 6, 7)),
      ),
      parseOutput = output,
    )

    assertEquals(expected = "The Title", actual = output.title)
    assertEquals(expected = "The Artist", actual = output.artist)
    assertEquals(expected = "The Album", actual = output.album)
    assertEquals(expected = "The Genre", actual = output.genre)
    assertEquals(expected = "The Narrator", actual = output.narrator)
    assertTrue(output.sawTags)
    assertFalse(output.tagsNeedRetriever)
  }

  @Test
  fun `prefers the artist over the album artist`() {
    val output = Mp4ChpaterExtractorOutput()

    IlstVisitor().visit(
      buffer = ilst(
        textItem(type = TYPE_ALBUM_ARTIST, text = "The Album Artist"),
        textItem(type = TYPE_ARTIST, text = "The Artist"),
      ),
      parseOutput = output,
    )

    assertEquals(expected = "The Artist", actual = output.artist)
  }

  @Test
  fun `falls back to the album artist`() {
    val output = Mp4ChpaterExtractorOutput()

    IlstVisitor().visit(
      buffer = ilst(textItem(type = TYPE_ALBUM_ARTIST, text = "The Album Artist")),
      parseOutput = output,
    )

    assertEquals(expected = "The Album Artist", actual = output.artist)
  }

  @Test
  fun `needs the retriever for freeform tags`() {
    val output = Mp4ChpaterExtractorOutput()

    IlstVisitor().visit(
      buffer = ilst(
        textItem(type = TYPE_TITLE, text = "The Title"),
        item(type = TYPE_FREEFORM, payload = byteArrayOf(0, 0, 0, 0)),
      ),
      parseOutput = output,
    )

    assertEquals(expected = "The Title", actual = output.title)
    assertTrue(output.tagsNeedRetriever)
  }

  @Test
  fun `needs the retriever for data that is not utf8`() {
    val output = Mp4ChpaterExtractorOutput()

    IlstVisitor().visit(
      buffer = ilst(dataItem(type = TYPE_TITLE, dataType = 13, payload = byteArrayOf(1, 2, 3))),
      parseOutput = output,
    )

    assertNull(output.title)
    assertTrue(output.tagsNeedRetriever)
  }

  @Test
  fun `stops at an item that does not fit`() {
    val output = Mp4ChpaterExtractorOutput()

    IlstVisitor().visit(
      buffer = ilst(
        textItem(type = TYPE_TITLE, text = "The Title"),
        // claims to be much bigger than the rest of the box
        ByteBuffer.allocate(8).putInt(1000).putInt(TYPE_ALBUM.toInt()).array(),
      ),
      parseOutput = output,
    )

    assertEquals(expected = "The Title", actual = output.title)
    assertNull(output.album)
  }

  private fun ilst(vararg items: ByteArray): ParsableByteArray {
    val bytes = ByteArrayOutputStream()
    items.forEach { bytes.write(it) }
    return ParsableByteArray(bytes.toByteArray())
  }

  private fun textItem(
    type: Long,
    text: String,
  ): ByteArray {
    return dataItem(type = type, dataType = 1, payload = text.toByteArray())
  }

  private fun dataItem(
    type: Long,
    dataType: Int,
    payload: ByteArray,
  ): ByteArray {
    val data = ByteBuffer.allocate(16 + payload.size)
      .putInt(16 + payload.size)
      .putInt(TYPE_DATA.toInt())
      .putInt(dataType)
      .putInt(0) // reserved
      .put(payload)
      .array()
    return item(type = type, payload = data)
  }

  private fun item(
    type: Long,
    payload: ByteArray,
  ): ByteArray {
    return ByteBuffer.allocate(8 + payload.size)
      .putInt(8 + payload.size)
      .putInt(type.toInt())
      .put(payload)
      .array()
  }

  private companion object {
    const val TYPE_DATA = 0x64617461L
    const val TYPE_TITLE = 0xA96E616DL
    const val TYPE_ARTIST = 0xA9415254L
    const val TYPE_ALBUM = 0xA9616C62L
    const val TYPE_GENRE = 0xA967656EL
    const val TYPE_COMPOSER = 0xA9777274L
    const val TYPE_ALBUM_ARTIST = 0x61415254L
    const val TYPE_FREEFORM = 0x2D2D2D2DL
    const val TYPE_COVER = 0x636F7672L
  }
}
