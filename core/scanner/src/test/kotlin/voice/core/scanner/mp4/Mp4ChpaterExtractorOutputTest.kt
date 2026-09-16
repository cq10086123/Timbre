package voice.core.scanner.mp4

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

internal class Mp4ChpaterExtractorOutputTest {

  @Test
  fun `uses the duration of the audio track`() {
    val output = Mp4ChpaterExtractorOutput()
    // a cover art track can be longer than the audio, so it must not win
    output.trackHandlers += "vide"
    output.trackDurationsMs += 129_000
    output.trackHandlers += "soun"
    output.trackDurationsMs += 119_211

    assertEquals(expected = 119_211L, actual = output.audioDurationMs)
  }

  @Test
  fun `no duration without an audio track`() {
    val output = Mp4ChpaterExtractorOutput()
    output.trackHandlers += "vide"
    output.trackDurationsMs += 129_000

    assertNull(output.audioDurationMs)
  }

  @Test
  fun `no duration when the tracks are not aligned`() {
    val output = Mp4ChpaterExtractorOutput()
    output.trackHandlers += "soun"
    output.trackHandlers += "vide"
    output.trackDurationsMs += 119_211

    assertNull(output.audioDurationMs)
  }
}
