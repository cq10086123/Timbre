package voice.core.update

import androidx.datastore.core.DataStore
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import voice.core.common.AppInfoProvider
import voice.core.common.DispatcherProvider
import voice.core.data.store.UpdateDismissedStore
import voice.core.data.store.UpdateLastCheckStore

data class UpdateAvailable(val versionName: String)

@SingleIn(AppScope::class)
@Inject
class UpdateNotifier(
  private val updateChecker: UpdateChecker,
  private val appInfoProvider: AppInfoProvider,
  private val dispatcherProvider: DispatcherProvider,
  @UpdateLastCheckStore private val lastCheckStore: DataStore<Int>,
  @UpdateDismissedStore private val dismissedStore: DataStore<String>,
) {

  private val scope = CoroutineScope(SupervisorJob() + dispatcherProvider.io)

  private val _update = MutableStateFlow<UpdateAvailable?>(null)

  /**
   * The available update, or null while none was found or the user dismissed
   * it. The check runs in the background and never blocks the app start.
   */
  val update: StateFlow<UpdateAvailable?> = _update

  fun onAppStarted() {
    scope.launch {
      // let the app settle first: the scan warms up caches and the first
      // screen is being composed, the update check is the least important
      // thing that happens after a start
      delay(CHECK_DELAY_MS)
      checkIfDue()
    }
  }

  private suspend fun checkIfDue() {
    val today = (System.currentTimeMillis() / MILLIS_PER_DAY).toInt()
    val lastChecked = lastCheckStore.data.first()
    if (today - lastChecked < 1) {
      return
    }
    // stored before the request so a device that is offline today does not
    // retry on every single start
    lastCheckStore.updateData { today }

    val latest = updateChecker.latestVersion() ?: return
    val current = appInfoProvider.versionName
    if (!isNewer(current, latest)) {
      return
    }
    if (latest == dismissedStore.data.first()) {
      // the user saw this release and pressed cancel, so stay quiet until a
      // newer one is published
      return
    }
    _update.value = UpdateAvailable(latest)
  }

  /** Closes the prompt for the shown release without remembering it as dismissed. */
  fun onUpdatePageOpened() {
    _update.value = null
  }

  /** Closes the prompt and stays quiet for the shown release. */
  suspend fun dismiss() {
    _update.value?.let { available ->
      dismissedStore.updateData { available.versionName }
    }
    _update.value = null
  }

  companion object {
    /** the page the update button opens in the browser */
    const val RELEASE_PAGE_URL = "https://github.com/cq10086123/Timbre/releases/latest"
  }
}

/**
 * Numeric comparison of dotted versions, so "1.0.9" < "1.0.10" holds and an
 * older device version never prompts for a downgrade.
 */
internal fun isNewer(
  current: String,
  latest: String,
): Boolean {
  val currentParts = current.removePrefix("v").split('.').map { it.toIntOrNull() ?: 0 }
  val latestParts = latest.removePrefix("v").split('.').map { it.toIntOrNull() ?: 0 }
  for (index in 0 until maxOf(currentParts.size, latestParts.size)) {
    val a = currentParts.getOrElse(index) { 0 }
    val b = latestParts.getOrElse(index) { 0 }
    if (a != b) {
      return b > a
    }
  }
  return false
}

private const val MILLIS_PER_DAY = 24 * 60 * 60 * 1000L

private const val CHECK_DELAY_MS = 10_000L
