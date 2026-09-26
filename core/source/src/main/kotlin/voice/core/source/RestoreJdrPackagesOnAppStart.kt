package voice.core.source

import android.app.Application
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import voice.core.common.DispatcherProvider
import voice.core.initializer.AppInitializer
import voice.core.logging.api.Logger

/**
 * Restores installed jdr packages on app start: reads the persisted package
 * dirs, loads the enabled bundles into their QuickJS runtimes and publishes
 * the sources to the registry. A broken package never blocks the app - it is
 * reported through the manager state instead.
 */
@ContributesIntoSet(AppScope::class)
@Inject
public class RestoreJdrPackagesOnAppStart(
  private val packageManager: JdrPackageManager,
  dispatcherProvider: DispatcherProvider,
) : AppInitializer {

  private val scope = CoroutineScope(SupervisorJob() + dispatcherProvider.io)

  override fun onAppStart(application: Application) {
    scope.launch {
      runCatching { packageManager.restore() }
        .onFailure { Logger.w("jdr package restore failed: ${it.message}") }
    }
  }
}
