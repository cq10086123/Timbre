package voice.core.online

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CaptchaExtractorTest {

  @Test
  fun `extracts characters in document order`() {
    val svg = """
      <svg xmlns="http://www.w3.org/2000/svg" width="120" height="44">
        <rect width="120" height="44" fill="#f3f5f9"/>
        <line x1="79" y1="26" x2="40" y2="22" stroke="#fb8c00"/>
        <text x="14" y="27" transform="rotate(12 14 27)">T</text>
        <text x="40" y="26" transform="rotate(-16 40 26)">N</text>
        <text x="66" y="26" transform="rotate(16 66 26)">4</text>
        <text x="92" y="26" transform="rotate(-8 92 26)">2</text>
      </svg>
    """.trimIndent()
    assertEquals("TN42", CaptchaExtractor.extract(svg))
  }

  @Test
  fun `returns empty for svg without text elements`() {
    val svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"><rect width=\"10\" height=\"10\"/></svg>"
    assertEquals("", CaptchaExtractor.extract(svg))
  }

  @Test
  fun `handles multi character text elements`() {
    val svg = "<svg><text x=\"1\" y=\"2\">ab</text><text x=\"3\" y=\"4\">C1</text></svg>"
    assertEquals("abC1", CaptchaExtractor.extract(svg))
  }
}
