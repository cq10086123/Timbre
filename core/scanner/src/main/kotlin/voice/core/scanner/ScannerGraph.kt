package voice.core.scanner

import androidx.media3.inspector.MetadataRetriever
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.Qualifier
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.sync.Semaphore
import voice.core.data.BookId
import voice.core.data.backup.BookImportState

@Qualifier
internal annotation class MediaAnalysisSemaphore

@ContributesTo(AppScope::class)
public interface ScannerGraph {

  /**
   * The running import, for consumers that must not write a half-imported
   * shelf - the WebDAV backup. Lives here because the scanner is the only
   * module that knows the progress, and it already depends on data/api.
   */
  @Provides
  @SingleIn(AppScope::class)
  public fun bookImportState(reporter: ScanProgressReporter): BookImportState {
    return object : BookImportState {
      override fun chapterTotals(): Map<BookId, Int> {
        return reporter.bookProgress.value.mapValues { (_, progress) -> progress.chaptersTotal }
      }
    }
  }

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
    // then acquire [analysisPermitsFor] the target permits each, which scales
    // the effective concurrency to the target while keeping this single
    // physical semaphore (kotlinx semaphores cannot change their permit count).
    //
    // [COVER_PERMITS_RESERVED] stays outside the analysis budget: the cover
    // pass runs while the import parses, and an analysis that could take every
    // permit would keep the shelf covers from appearing for a whole batch of
    // chapters at a time.
    MetadataRetriever.setMaximumParallelRetrievals(MAX_IMPORT_PARALLELISM)
    return Semaphore(ANALYSIS_PERMITS + COVER_PERMITS_RESERVED)
  }
}

/**
 * How many permits a single chapter analysis takes, so that at most [target]
 * analyses run at the same time. The target is the user's import parallelism
 * setting; out of range values fall back to the closest supported level.
 */
internal fun analysisPermitsFor(target: Int): Int {
  val parallelism = target.coerceIn(1, MAX_IMPORT_PARALLELISM)
  require(ANALYSIS_PERMITS % parallelism == 0) {
    "ANALYSIS_PERMITS ($ANALYSIS_PERMITS) must divide evenly by the import parallelism ($parallelism)"
  }
  return ANALYSIS_PERMITS / parallelism
}

// 6 = MAX_IMPORT_PARALLELISM squared: it divides evenly by every target
// (6 / 1 = 6, 6 / 2 = 3, 6 / 3 = 2), so multi-permit acquisition yields
// exactly the chosen concurrency.
internal const val ANALYSIS_PERMITS = 6

// always left free for the cover pass, which shares this semaphore: covers used
// to run beside a serial import and must not start waiting for chapter batches
internal const val COVER_PERMITS_RESERVED = 1
