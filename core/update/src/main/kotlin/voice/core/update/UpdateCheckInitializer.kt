package voice.core.update

import android.app.Application
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import voice.core.initializer.AppInitializer

@ContributesIntoSet(AppScope::class)
class UpdateCheckInitializer(private val updateNotifier: UpdateNotifier) : AppInitializer {

  override fun onAppStart(application: Application) {
    // the check itself is delayed and runs on a background dispatcher, this
    // only schedules it
    updateNotifier.onAppStarted()
  }
}
