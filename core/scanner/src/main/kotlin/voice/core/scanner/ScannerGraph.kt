package voice.core.scanner

import androidx.media3.inspector.MetadataRetriever
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
  private fun mediaAnalysisSemaphore(): Semaphore {
    // analyzing a chapter is dominated by io (opening the file through the
    // documents provider and reading its headers), so more workers than cores
    // speed up importing a big book
    val permits = (Runtime.getRuntime().availableProcessors() * 2)
      .coerceIn(MIN_ANALYSIS_PARALLELISM, MAX_ANALYSIS_PARALLELISM)
    // the retriever caps the number of parallel retrievals on its own, so it
    // has to be raised together with our permits
    MetadataRetriever.setMaximumParallelRetrievals(permits)
    return Semaphore(permits)
  }
}

private const val MIN_ANALYSIS_PARALLELISM = 6
private const val MAX_ANALYSIS_PARALLELISM = 12
