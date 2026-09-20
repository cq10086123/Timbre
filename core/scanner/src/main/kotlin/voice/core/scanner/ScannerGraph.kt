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
    // away or is queued behind the import. The default target of one analysis
    // at a time keeps "switch a chapter and it plays immediately" holding on
    // slow storage.
    //
    // The user can raise the target to [MAX_IMPORT_PARALLELISM]; operations
    // then acquire ANALYSIS_PERMITS / target permits each, which scales the
    // effective concurrency to the target while keeping this single physical
    // semaphore (kotlinx semaphores cannot change their permit count).
    MetadataRetriever.setMaximumParallelRetrievals(MAX_IMPORT_PARALLELISM)
    return Semaphore(ANALYSIS_PERMITS)
  }
}

// 6 = MAX_IMPORT_PARALLELISM squared: it divides evenly by every target
// (6 / 1 = 6, 6 / 2 = 3, 6 / 3 = 2), so multi-permit acquisition yields
// exactly the chosen concurrency.
internal const val ANALYSIS_PERMITS = 6
