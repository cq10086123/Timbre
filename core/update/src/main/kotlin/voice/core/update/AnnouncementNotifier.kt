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
import voice.core.common.DispatcherProvider
import voice.core.data.store.AnnouncementDismissedStore
import java.security.MessageDigest

/**
 * Shows the text of `announcement.txt` as a dialog.
 *
 * The contract: the announcement is read once per app start; a text the user
 * has not answered yet pops up, and any edit of the file pops up again - the
 * fingerprint stored by [neverShowAgain] only silences the exact text it was
 * taken from.
 */
@SingleIn(AppScope::class)
@Inject
class AnnouncementNotifier(
  private val checker: AnnouncementChecker,
  private val dispatcherProvider: DispatcherProvider,
  @AnnouncementDismissedStore private val dismissedStore: DataStore<String>,
) {

  private val scope = CoroutineScope(SupervisorJob() + dispatcherProvider.io)

  private val _prompt = MutableStateFlow<String?>(null)

  /** The announcement the dialog should show, or null while it stays closed. */
  val prompt: StateFlow<String?> = _prompt

  fun onAppStarted() {
    scope.launch {
      // same quiet start as the update check: the announcement is the least
      // important thing that happens while the shelf is composing
      delay(CHECK_DELAY_MS)
      check()
    }
  }

  private suspend fun check() {
    val announcement = checker.current() ?: return
    if (fingerprintOf(announcement) == dismissedStore.data.first()) return
    _prompt.value = announcement
  }

  /**
   * Closes the dialog for this launch. The announcement keeps coming back on
   * the next start until the user asks to never see it - "知道了" answers the
   * current dialog, it does not silence the text forever.
   */
  fun onKnow() {
    _prompt.value = null
  }

  /** Closes the dialog and stays quiet for this exact text; an edit pops again. */
  suspend fun neverShowAgain() {
    val announcement = _prompt.value ?: return
    val fingerprint = fingerprintOf(announcement)
    dismissedStore.updateData { fingerprint }
    _prompt.value = null
  }

  private companion object {
    const val CHECK_DELAY_MS = 10_000L
  }
}

/** Cheap, collision free identity of an announcement text. */
internal fun fingerprintOf(announcement: String): String {
  val digest = MessageDigest.getInstance("SHA-256").digest(announcement.toByteArray())
  return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
}
