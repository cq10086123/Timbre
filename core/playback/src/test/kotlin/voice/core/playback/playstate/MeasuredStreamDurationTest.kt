package voice.core.playback.playstate

import androidx.media3.common.C
import kotlin.test.Test
import kotlin.test.assertEquals

class MeasuredStreamDurationTest {

  @Test
  fun `keeps the duration of a stream that is shorter than its clip`() {
    // the real audio ends before the mark the item is clipped to, so the
    // reported duration really is the length of the content
    assertEquals(
      expected = 900_000L,
      actual = measuredStreamDurationMs(
        reportedDurationMs = 900_000L,
        declaredDurationMs = 1_800_000L,
      ),
    )
  }

  @Test
  fun `keeps a measurement of an item without a declared duration`() {
    assertEquals(
      expected = 42L,
      actual = measuredStreamDurationMs(
        reportedDurationMs = 42L,
        declaredDurationMs = null,
      ),
    )
    // media3 reports TIME_UNSET when the metadata carries no duration
    assertEquals(
      expected = 42L,
      actual = measuredStreamDurationMs(
        reportedDurationMs = 42L,
        declaredDurationMs = C.TIME_UNSET,
      ),
    )
  }

  @Test
  fun `drops the clip end of a clipped item`() {
    // a chapter assembled on the 30 minute placeholder: the player reports the
    // clip, not the length of the stream behind it. Keeping it would overwrite
    // the measured duration with the placeholder.
    assertEquals(
      expected = 0L,
      actual = measuredStreamDurationMs(
        reportedDurationMs = 1_799_999L,
        declaredDurationMs = 1_799_999L,
      ),
    )
    assertEquals(
      expected = 0L,
      actual = measuredStreamDurationMs(
        reportedDurationMs = 2_700_000L,
        declaredDurationMs = 1_799_999L,
      ),
    )
  }

  @Test
  fun `drops durations that are not a measurement`() {
    assertEquals(
      expected = 0L,
      actual = measuredStreamDurationMs(
        reportedDurationMs = C.TIME_UNSET,
        declaredDurationMs = null,
      ),
    )
    assertEquals(
      expected = 0L,
      actual = measuredStreamDurationMs(
        reportedDurationMs = 0L,
        declaredDurationMs = null,
      ),
    )
    assertEquals(
      expected = 0L,
      actual = measuredStreamDurationMs(
        reportedDurationMs = -1L,
        declaredDurationMs = null,
      ),
    )
  }
}
