package voice.core.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class AnnouncementCheckerTest {

  private val checker = AnnouncementChecker()

  @Test
  fun `windows line endings and surrounding blanks are normalized`() {
    assertEquals("第一行\n第二行", checker.normalize("  第一行\r\n第二行 \r\n"))
  }

  @Test
  fun `a blank file shows no announcement`() {
    // the maintainers keep the file empty while there is nothing to say
    assertNull(checker.normalize(""))
    assertNull(checker.normalize("\n\n   \r\n"))
  }

  @Test
  fun `the same text always fingerprints the same`() {
    assertEquals(fingerprintOf("维护通知"), fingerprintOf("维护通知"))
  }

  @Test
  fun `editing the text changes the fingerprint so the dialog pops again`() {
    // this is what makes a new announcement override "don't show again"
    assertNotEquals(fingerprintOf("今晚维护"), fingerprintOf("今晚维护\n明早恢复"))
  }
}
