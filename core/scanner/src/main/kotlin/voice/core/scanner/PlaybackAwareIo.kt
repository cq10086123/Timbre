package voice.core.scanner

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import voice.core.common.PlaybackIoGate

/**
 * Takes a scanner io slot, but only while playback is not loading.
 *
 * The order matters: the playback check happens **before** the slot is taken.
 * Holding a slot while waiting for the player parks analyses and then releases
 * all of them at once as soon as the sound is up, which is exactly the moment
 * the player needs the storage for itself.
 *
 * A read that is already in flight cannot be cancelled, so the only lever to
 * hand the storage to the player is to keep the number of in-flight reads low
 * in the first place. That is why the caller passes a [Semaphore] with a low
 * permit count instead of relying on this gate alone.
 */
internal suspend fun <T> PlaybackIoGate.withScannerIoSlot(
  semaphore: Semaphore,
  block: suspend () -> T,
): T = whilePlaybackLoads { semaphore.withPermit { block() } }
