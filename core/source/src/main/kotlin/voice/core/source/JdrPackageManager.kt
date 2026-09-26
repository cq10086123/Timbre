package voice.core.source

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import voice.core.logging.api.Logger
import voice.core.source.jdr.JdrFormatException
import voice.core.source.runtime.SourceScriptException
import voice.core.source.jdr.JdrLoader
import voice.core.source.jdr.JdrManifest
import voice.core.source.jdr.JdrPackage
import java.io.File

/** One installed package with its user-facing state. */
@Serializable
public data class InstalledJdrPackage(
  val manifest: JdrManifest,
  val enabled: Boolean = true,
  /** Per source overrides; absent keys follow [enabled]. */
  val sourceEnabled: Map<String, Boolean> = emptyMap(),
  val installedAt: Long = 0L,
  val updatedAt: Long = 0L,
  /** Where the package came from (url or file name), for display. */
  val origin: String = "",
  /** Set when the bundle failed to load; shown in the manager. */
  val lastError: String = "",
)

/** State the manager reports to the ui. */
public data class JdrPackageManagerState(
  val packages: List<InstalledJdrPackage> = emptyList(),
)

/**
 * Installs, updates, enables and removes jdr packages. Packages live under
 * `files/jdr/packages/<packageId>/` as `manifest.json`, `bundle.js` and
 * `meta.json`; the registry and the javascript runtimes are kept in sync
 * with the persisted state on every mutation and on app start.
 */
public class JdrPackageManager(
  filesDir: File,
  private val loader: JdrLoader,
  private val registry: SourceRegistry,
  private val runtimePool: JdrRuntimePool,
  private val httpClient: OkHttpClient,
) {

  private val packagesDir = File(filesDir, PACKAGES_DIR)
  private val json = Json {
    ignoreUnknownKeys = true
    prettyPrint = false
  }

  private val stateFlow = MutableStateFlow(JdrPackageManagerState())
  public val state: StateFlow<JdrPackageManagerState> = stateFlow.asStateFlow()

  private val mutex = Mutex()

  /** Reads the persisted packages and loads the enabled ones. Idempotent. */
  public suspend fun restore(): Unit = mutex.withLock {
    packagesDir.listFiles { f -> f.isDirectory }
      ?.mapNotNull { readMeta(it) }
      .orEmpty()
      .forEach { meta -> syncLocked(meta) }
    publishAll()
  }

  /** Downloads a package from [url] and installs it. */
  public suspend fun installFromUrl(url: String): InstalledJdrPackage {
    val trimmed = url.trim()
    require(trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
      "Only http(s) urls are supported"
    }
    val bytes = download(trimmed)
    return install(bytes, origin = trimmed)
  }

  /** Installs [bytes] as a jdr package; an existing packageId is replaced. */
  public suspend fun install(bytes: ByteArray, origin: String): InstalledJdrPackage = mutex.withLock {
    val parsed: JdrPackage = try {
      loader.load(bytes)
    } catch (e: JdrFormatException) {
      throw IllegalArgumentException(e.message, e)
    }
    val packageId = parsed.manifest.packageId
    val dir = File(packagesDir, packageId)
    val existing = readMeta(dir)

    val tmp = File(packagesDir, "$packageId.tmp")
    tmp.deleteRecursively()
    tmp.mkdirs()
    try {
      File(tmp, MANIFEST_FILE).writeText(json.encodeToString(JdrManifest.serializer(), parsed.manifest))
      File(tmp, BUNDLE_FILE).writeText(parsed.bundle)
      val meta = InstalledJdrPackage(
        manifest = parsed.manifest,
        enabled = existing?.enabled ?: true,
        sourceEnabled = existing?.sourceEnabled ?: emptyMap(),
        installedAt = existing?.installedAt ?: System.currentTimeMillis(),
        updatedAt = System.currentTimeMillis(),
        origin = origin.ifBlank { existing?.origin.orEmpty() },
        lastError = "",
      )
      File(tmp, META_FILE).writeText(json.encodeToString(InstalledJdrPackage.serializer(), meta))
      if (dir.exists()) dir.deleteRecursively()
      if (!tmp.renameTo(dir)) throw IllegalStateException("could not finalize package dir")
      syncLocked(meta)
      publish(meta)
      meta
    } finally {
      tmp.deleteRecursively()
    }
  }

  public suspend fun uninstall(packageId: String): Unit = mutex.withLock {
    runtimePool.unload(packageId)
    registry.removePackage(packageId)
    File(packagesDir, packageId).deleteRecursively()
    publishAll()
  }

  public suspend fun setPackageEnabled(packageId: String, enabled: Boolean): Unit = mutex.withLock {
    val dir = File(packagesDir, packageId)
    val meta = readMeta(dir) ?: return@withLock
    val updated = meta.copy(enabled = enabled, lastError = "")
    writeMeta(dir, updated)
    syncLocked(updated)
    publish(updated)
  }

  public suspend fun setSourceEnabled(packageId: String, sourceId: String, enabled: Boolean): Unit = mutex.withLock {
    val dir = File(packagesDir, packageId)
    val meta = readMeta(dir) ?: return@withLock
    val updated = meta.copy(sourceEnabled = meta.sourceEnabled + (sourceId to enabled))
    writeMeta(dir, updated)
    syncLocked(updated)
    publish(updated)
  }

  /** Reloads the package after install/toggle: registry and runtime state. */
  private suspend fun syncLocked(meta: InstalledJdrPackage) {
    val packageId = meta.manifest.packageId
    registry.removePackage(packageId)
    runtimePool.unload(packageId)
    if (!meta.enabled) return

    val dir = File(packagesDir, packageId)
    val bundle = File(dir, BUNDLE_FILE).takeIf { it.isFile }?.readText() ?: run {
      publish(meta.copy(lastError = "bundle missing on disk"))
      return
    }
    try {
      val registered = runtimePool.load(packageId, bundle, "Jdr/$packageId")
      val declared = meta.manifest.sources.map { it.id }.toSet()
      val missing = declared - registered
      if (missing.isNotEmpty()) {
        throw SourceScriptException("bundle did not register sources: ${missing.joinToString()}")
      }
      val sources = meta.manifest.sources
        .filter { it.id in registered }
        .map { info ->
          val enabled = meta.sourceEnabled[info.id] ?: true
          if (enabled) {
            JdrSource(
              pool = runtimePool,
              packageId = packageId,
              sourceId = info.id,
              displayName = info.name.ifBlank { info.id },
            )
          } else {
            null
          }
        }
        .filterNotNull()
      registry.replacePackage(packageId, sources)
      publish(meta.copy(lastError = ""))
    } catch (e: Exception) {
      runtimePool.unload(packageId)
      registry.removePackage(packageId)
      Logger.w("jdr package $packageId failed to load: ${e.message}")
      publish(meta.copy(lastError = e.message ?: e.javaClass.simpleName))
    }
  }

  private suspend fun download(url: String): ByteArray {
    val request = okhttp3.Request.Builder().url(url).build()
    httpClient.newCall(request).execute().use { response ->
      if (!response.isSuccessful) {
        throw IllegalStateException("download failed with http ${response.code}")
      }
      val body = response.body
      val source = body.source()
      source.request(JdrLoader.MAX_PACKAGE_BYTES + 1)
      val buffer = source.buffer.clone()
      if (buffer.size > JdrLoader.MAX_PACKAGE_BYTES) {
        throw IllegalStateException("package too large")
      }
      return source.readByteArray()
    }
  }

  private fun writeMeta(dir: File, meta: InstalledJdrPackage) {
    dir.mkdirs()
    File(dir, META_FILE).writeText(json.encodeToString(InstalledJdrPackage.serializer(), meta))
  }

  private fun readMeta(dir: File): InstalledJdrPackage? = try {
    if (!dir.isDirectory) {
      null
    } else {
      json.decodeFromString(
        InstalledJdrPackage.serializer(),
        File(dir, META_FILE).takeIf { it.isFile }?.readText() ?: return null,
      )
    }
  } catch (e: Exception) {
    Logger.w("unreadable jdr package dir ${dir.name}: ${e.message}")
    null
  }

  private fun publish(updated: InstalledJdrPackage) {
    stateFlow.value = JdrPackageManagerState(
      (stateFlow.value.packages.filterNot { it.manifest.packageId == updated.manifest.packageId } + updated)
        .sortedBy { it.manifest.packageId },
    )
  }

  private fun publishAll() {
    val onDisk = packagesDir.listFiles { f -> f.isDirectory }
      ?.mapNotNull { readMeta(it) }
      .orEmpty()
      .sortedBy { it.manifest.packageId }
    stateFlow.value = JdrPackageManagerState(onDisk)
  }

  public companion object {
    private const val TAG = "JdrPackageManager"
    public const val PACKAGES_DIR: String = "jdr/packages"
    public const val MANIFEST_FILE: String = "manifest.json"
    public const val BUNDLE_FILE: String = "bundle.js"
    public const val META_FILE: String = "meta.json"
  }
}
