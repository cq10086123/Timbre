package voice.core.online

import android.app.Application
import androidx.datastore.core.DataStore
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import voice.core.common.DispatcherProvider
import voice.core.initializer.AppInitializer
import voice.core.logging.api.Logger

/**
 * Continues the manual cache jobs a previous process did not finish, so
 * "一边听书一边缓存" survives a restart instead of silently stopping.
 *
 * Only unfinished jobs are picked up (the manager drops a job once it is done,
 * cancelled or its book deleted), and only while the online source is enabled.
 * A job that wakes up on mobile data asks the user first - a cache started on
 * wifi must not continue on a metered connection unasked.
 */
@ContributesIntoSet(AppScope::class)
@Inject
public class ResumeOnlineCachingOnAppStart(
  private val cacheManager: OnlineBookCacheManager,
  @OnlineSourceEnabledStore private val enabledStore: DataStore<Boolean>,
  dispatcherProvider: DispatcherProvider,
) : AppInitializer {

  private val scope = CoroutineScope(SupervisorJob() + dispatcherProvider.io)

  override fun onAppStart(application: Application) {
    scope.launch {
      val enabled = runCatching { enabledStore.data.first() }.getOrDefault(false)
      if (!enabled) return@launch
      Logger.i("Resuming unfinished online cache jobs")
      cacheManager.resumePending()
    }
  }
}
