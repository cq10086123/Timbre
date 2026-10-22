package voice.core.update

import android.app.Application
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import voice.core.initializer.AppInitializer

@ContributesIntoSet(AppScope::class)
class AnnouncementCheckInitializer(private val announcementNotifier: AnnouncementNotifier) : AppInitializer {

  override fun onAppStart(application: Application) {
    // the fetch is delayed and runs on a background dispatcher, this only
    // schedules it
    announcementNotifier.onAppStarted()
  }
}
