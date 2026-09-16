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
    // analyzing a chapter is io bound: opening the file through the documents
    // provider is also the path the player takes to open the next chapter.
    // Too many parallel analyses starve the provider and delay playback, so
    // the parallelism stays low even though imports take a bit longer.
    val permits = Runtime.getRuntime().availableProcessors()
      .coerceIn(MIN_ANALYSIS_PARALLELISM, MAX_ANALYSIS_PARALLELISM)
    // the retriever caps the number of parallel retrievals on its own, so it
    // has to be raised together with our permits
    MetadataRetriever.setMaximumParallelRetrievals(permits)
    return Semaphore(permits)
  }
}

private const val MIN_ANALYSIS_PARALLELISM = 3
private const val MAX_ANALYSIS_PARALLELISM = 6
