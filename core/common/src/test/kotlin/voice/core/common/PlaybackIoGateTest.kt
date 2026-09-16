package voice.core.common

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackIoGateTest {

  @Test
  fun `runs immediately when playback is not loading`() = runTest {
    val gate = PlaybackIoGate()

    var ran = false
    gate.whilePlaybackLoads { ran = true }

    assertTrue(ran)
  }

  @Test
  fun `waits while playback is loading and resumes afterwards`() = runTest {
    val gate = PlaybackIoGate()
    gate.setAwaitingPlayback(true)

    var ran = false
    val job = launch {
      gate.whilePlaybackLoads { ran = true }
    }
    runCurrent()
    assertFalse(ran)

    gate.setAwaitingPlayback(false)
    runCurrent()
    job.join()
    assertTrue(ran)
  }
}
