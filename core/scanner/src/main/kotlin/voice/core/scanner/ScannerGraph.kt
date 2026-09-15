package voice.core.scanner

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.Qualifier
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.sync.Semaphore

@Qualifier
internal annotation class MediaAnalysisSemaphore

@ContributesTo(AppScope::class)
public interface ScannerGraph {

  @Provides
  @SingleIn(AppScope::class)
  @MediaAnalysisSemaphore
  private fun mediaAnalysisSemaphore(): Semaphore = Semaphore(4)
}
