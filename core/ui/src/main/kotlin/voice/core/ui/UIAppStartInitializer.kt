package voice.core.ui

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.datastore.core.DataStore
import coil.Coil
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import voice.core.data.ThemeMode
import voice.core.data.store.ThemeModeStore
import voice.core.initializer.AppInitializer

@ContributesIntoSet(AppScope::class)
class UIAppStartInitializer(
  @ThemeModeStore
  private val themeModeStore: DataStore<ThemeMode>,
  private val scope: CoroutineScope,
) : AppInitializer {

  override fun onAppStart(application: Application) {
    Coil.setImageLoader(
      ImageLoader.Builder(application)
        // Cover files are rewritten under a stable path; last-modified keys
        // thrash the disk cache after every cover scan without helping.
        .addLastModifiedToFileCacheKey(false)
        // Shelf covers are small; keep more of them resident while scrolling.
        .memoryCache {
          MemoryCache.Builder(application)
            .maxSizePercent(0.25)
            .build()
        }
        .diskCache {
          DiskCache.Builder()
            .directory(application.cacheDir.resolve("image_cache"))
            .maxSizeBytes(64L * 1024L * 1024L)
            .build()
        }
        .crossfade(false)
        .build(),
    )
    themeModeStore.data
      .distinctUntilChanged()
      .onEach { themeMode ->
        val nightMode = when (themeMode) {
          ThemeMode.FollowSystem -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
          ThemeMode.Light -> AppCompatDelegate.MODE_NIGHT_NO
          ThemeMode.Dark -> AppCompatDelegate.MODE_NIGHT_YES
        }
        AppCompatDelegate.setDefaultNightMode(nightMode)
      }
      .launchIn(scope)
  }
}
