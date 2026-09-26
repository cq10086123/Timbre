package voice.core.source.di

import android.app.Application
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.Qualifier
import dev.zacsweers.metro.SingleIn
import okhttp3.OkHttpClient
import voice.core.source.JdrPackageManager
import voice.core.source.jdr.JdrLoader
import voice.core.source.JdrRuntimePool
import voice.core.source.SourceRegistry
import java.util.concurrent.TimeUnit

/** OkHttpClient used for plugin http calls and jdr package downloads. */
@Qualifier
public annotation class JdrHttpClient

/**
 * Contributes the jdr plugin stack to the app graph: the registry, the
 * runtime pool, the package manager and a dedicated http client.
 */
@ContributesTo(AppScope::class)
public interface SourceGraph {

  @Provides
  @SingleIn(AppScope::class)
  @JdrHttpClient
  public fun jdrHttpClient(): OkHttpClient =
    OkHttpClient.Builder()
      .connectTimeout(15, TimeUnit.SECONDS)
      .readTimeout(30, TimeUnit.SECONDS)
      .build()

  @Provides
  @SingleIn(AppScope::class)
  public fun sourceRegistry(): SourceRegistry = SourceRegistry()

  @Provides
  @SingleIn(AppScope::class)
  public fun jdrRuntimePool(@JdrHttpClient httpClient: OkHttpClient): JdrRuntimePool =
    JdrRuntimePool(httpClient)

  @Provides
  public fun jdrLoader(): JdrLoader = JdrLoader()

  @Provides
  @SingleIn(AppScope::class)
  public fun jdrPackageManager(
    application: Application,
    loader: JdrLoader,
    registry: SourceRegistry,
    runtimePool: JdrRuntimePool,
    @JdrHttpClient httpClient: OkHttpClient,
  ): JdrPackageManager = JdrPackageManager(
    filesDir = application.filesDir,
    loader = loader,
    registry = registry,
    runtimePool = runtimePool,
    httpClient = httpClient,
  )
}
