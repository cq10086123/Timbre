package voice.core.source

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import voice.core.logging.api.Logger
import voice.core.source.runtime.QuickJsSourceRuntime
import voice.core.source.runtime.SourceScriptException
import voice.core.source.runtime.SourceLogSink

/**
 * Owns one [QuickJsSourceRuntime] per installed package. Runtimes are created
 * lazily on first use and closed when a package is updated, disabled or
 * removed, so an idle jdr package costs nothing.
 */
public class JdrRuntimePool(
  private val httpClient: OkHttpClient,
) : AutoCloseable {

  private data class Loaded(
    val runtime: QuickJsSourceRuntime,
    val sourceIds: Set<String>,
  )

  private val mutex = Mutex()
  private val runtimes = LinkedHashMap<String, Loaded>()

  /** Loads the bundle and returns the registered source ids. */
  public suspend fun load(packageId: String, bundle: String, logTag: String): Set<String> =
    mutex.withLock {
      unloadLocked(packageId)
      val logSink = SourceLogSink { message -> Logger.i("[$logTag] $message") }
      val runtime = QuickJsSourceRuntime.create(bundle, httpClient, logSink)
      try {
        val metas = runtime.registeredSources()
        if (metas.isEmpty()) {
          runtime.close()
          throw SourceScriptException("bundle registered no sources")
        }
        runtimes[packageId] = Loaded(runtime, metas.map { it.id }.toSet())
        metas.map { it.id }.toSet()
      } catch (e: Exception) {
        runtime.close()
        throw e
      }
    }

  /**
   * Calls a method on one source of [packageId]. Fails when the package is
   * not loaded or did not register [sourceId].
   */
  public suspend fun call(
    packageId: String,
    sourceId: String,
    method: String,
    args: List<String>,
  ): String {
    val runtime = mutex.withLock {
      runtimes[packageId]?.runtime
        ?: throw SourceScriptException("package $packageId is not loaded")
    }
    return runtime.call(sourceId, method, args)
  }

  public suspend fun isLoaded(packageId: String): Boolean =
    mutex.withLock { runtimes.containsKey(packageId) }

  public suspend fun unload(packageId: String) {
    mutex.withLock { unloadLocked(packageId) }
  }

  private fun unloadLocked(packageId: String) {
    runtimes.remove(packageId)?.runtime?.close()
  }

  override fun close() {
    // best effort: closing native contexts from a non-suspending path
    runtimes.values.forEach { loaded ->
      try {
        loaded.runtime.close()
      } catch (_: Exception) {
        // best effort close on shutdown
      }
    }
    runtimes.clear()
  }
}
