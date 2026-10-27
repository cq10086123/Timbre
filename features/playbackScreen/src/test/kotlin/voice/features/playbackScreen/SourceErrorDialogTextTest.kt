package voice.features.playbackScreen

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import voice.core.strings.R as StringsR

class SourceErrorDialogTextTest {

  @Test
  fun `okhttp timeout message maps to the localized timeout text`() {
    assertEquals(StringsR.string.playback_online_error_timeout, frameworkErrorRes("timeout"))
  }

  @Test
  fun `okhttp canceled message maps to the localized canceled text`() {
    assertEquals(StringsR.string.playback_online_error_canceled, frameworkErrorRes("canceled"))
  }

  @Test
  fun `source wording is never remapped`() {
    assertNull(frameworkErrorRes("同一IP每 30 秒仅可请求 1 次音频，请 26 秒后再试"))
    assertNull(frameworkErrorRes("未找到第 1 集"))
    assertNull(frameworkErrorRes("timeouted"))
  }

  @Test
  fun `raw text keeps stripping framework prefixes and js stack frames`() {
    val text = rawSourceErrorText(
      "com.dokar.quickjs.QuickJsException: Error: 未找到第 1 集\n    at audio (l.js:154:37)",
    )
    assertEquals("未找到第 1 集", text)
  }
}
