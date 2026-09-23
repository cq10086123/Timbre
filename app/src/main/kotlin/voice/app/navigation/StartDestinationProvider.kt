package voice.app.navigation

import android.content.Intent
import androidx.datastore.core.DataStore
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.first
import voice.app.MainActivity
import voice.core.data.BookId
import voice.core.data.folders.AudiobookFolders
import voice.core.data.store.CurrentBookStore
import voice.core.data.store.OnboardingCompletedStore
import voice.navigation.Destination

/**
 * Resolved once when [MainActivity] starts. Keeps DataStore reads and play
 * side effects out of composition so recomposition cannot block the main
 * thread or re-trigger playback.
 */
data class StartDestination(
  val destinations: List<Destination.Compose>,
  val shouldPlayCurrent: Boolean = false,
)

@Inject
class StartDestinationProvider(
  @OnboardingCompletedStore
  private val onboardingCompletedStore: DataStore<Boolean>,
  private val audiobookFolders: AudiobookFolders,
  @CurrentBookStore
  private val currentBookStore: DataStore<BookId?>,
) {

  suspend operator fun invoke(intent: Intent): StartDestination {
    if (showOnboarding()) {
      return StartDestination(listOf(Destination.OnboardingWelcome))
    }

    val goToBook = intent.getBooleanExtra(MainActivity.NI_GO_TO_BOOK, false)
    if (goToBook) {
      val bookId = currentBookStore.data.first()
      if (bookId != null) {
        return StartDestination(listOf(Destination.BookOverview, Destination.Playback(bookId)))
      }
    }

    if (intent.action == "playCurrent") {
      val bookId = currentBookStore.data.first()
      if (bookId != null) {
        return StartDestination(
          destinations = listOf(Destination.BookOverview, Destination.Playback(bookId)),
          shouldPlayCurrent = true,
        )
      }
    }
    return StartDestination(listOf(Destination.BookOverview))
  }

  private suspend fun showOnboarding(): Boolean {
    return when {
      onboardingCompletedStore.data.first() -> false
      audiobookFolders.hasAnyFolders() -> false
      else -> true
    }
  }
}
