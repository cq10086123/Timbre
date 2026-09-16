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
    // A read that is already in flight cannot be cancelled, so the number of
    // reads in flight decides whether a chapter switch gets its file right
    // away or is queued behind the import. One analysis at a time is what
    // makes "switch a chapter and it plays immediately" hold on slow storage;
    // on fast storage imports only get a little slower, and on slow storage
    // they get faster because the reads stop thrashing each other.
    val permits = ANALYSIS_PARALLELISM
    // the retriever caps the number of parallel retrievals on its own, so it
    // has to be raised together with our permits
    MetadataRetriever.setMaximumParallelRetrievals(permits)
    return Semaphore(permits)
  }
}

private const val ANALYSIS_PARALLELISM = 1
