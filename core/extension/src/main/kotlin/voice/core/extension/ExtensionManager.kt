package voice.core.extension

import android.app.Application
import androidx.datastore.core.DataStore
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import voice.core.extension.engine.ExtensionManifest
import voice.core.extension.engine.JdrArchive
import voice.core.extension.engine.JdrFormatException
import java.io.File
import java.util.concurrent.TimeUnit

/** Thrown when installing a package fails with a user presentable reason. */
public class ExtensionInstallException public constructor(
  message: String,
  cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Installs, updates and removes .jdr extension packages. Package files live
 * under `filesDir/extensions/<manifest.id>/`, the registry in a DataStore.
 */
@Inject
@SingleIn(AppScope::class)
public class ExtensionManager(
  private val application: Application,
  private val storeFactory: ExtensionStoreFactory,
) {

  private val packagesStore: DataStore<List<InstalledExtensionPackage>> by lazy {
    storeFactory.packages()
  }

  // serializes install/uninstall/enable mutations (read-modify-write on the
  // registry must not interleave) and reuses one download client
  private val mutationMutex = Mutex()

  private val downloadClient by lazy {
    OkHttpClient.Builder()
      .connectTimeout(15, TimeUnit.SECONDS)
      .readTimeout(30, TimeUnit.SECONDS)
      .build()
  }

  public fun packagesStore(): DataStore<List<InstalledExtensionPackage>> = packagesStore

  public fun packageDir(manifestId: String): File = File(application.filesDir, "extensions/$manifestId")

  public suspend fun installed(): List<InstalledExtensionPackage> = packagesStore.data.first()

  /**
   * Validates and installs a .jdr package. A package with the same manifest
   * id is updated in place (keeping per-source enabled states); a source id
   * already claimed by another package is rejected.
   */
  public suspend fun install(bytes: ByteArray): ExtensionManifest {
    // inflating and validating a package is cpu and io heavy. It used to run on
    // the caller's dispatcher, which is the main thread of both import screens,
    // so a large package froze the ui or ran out of memory before anything was
    // even written to disk.
    return mutationMutex.withLock { withContext(Dispatchers.IO) { installLocked(bytes) } }
  }

  private suspend fun installLocked(bytes: ByteArray): ExtensionManifest {
    val archive = try {
      JdrArchive.parse(bytes)
    } catch (e: JdrFormatException) {
      throw ExtensionInstallException(e.message ?: "包格式错误", e)
    }
    val manifest = archive.manifest
    val current = installed()
    current
      .filter { it.manifest.id != manifest.id }
      .flatMap { it.manifest.sources }
      .firstOrNull { existing -> manifest.sources.any { it.id == existing.id } }
      ?.let {
        throw ExtensionInstallException("源 id ${it.id} 已被其他扩展包占用")
      }

    val dir = packageDir(manifest.id)
    withContext(Dispatchers.IO) {
      dir.deleteRecursively()
      dir.mkdirs()
      File(dir, JdrArchive.MANIFEST_ENTRY).writeText(
        storeFactory.json.encodeToString(ExtensionManifest.serializer(), manifest),
        Charsets.UTF_8,
      )
      archive.scripts.forEach { (name, content) ->
        File(dir, name).also { it.parentFile?.mkdirs() }.writeText(content, Charsets.UTF_8)
      }
    }

    packagesStore.updateData { packages ->
      val existing = packages.firstOrNull { it.manifest.id == manifest.id }
      val merged = InstalledExtensionPackage(
        manifest = manifest,
        installedAtMillis = System.currentTimeMillis(),
        // keep enabled flags of sources that still exist; new sources start enabled
        sourceEnabled = existing?.sourceEnabled.orEmpty()
          .filterKeys { key -> manifest.sources.any { it.id == key } },
      )
      listOf(merged) + packages.filterNot { it.manifest.id == manifest.id }
    }
    return manifest
  }

  /** Downloads a .jdr package from [url] and installs it. */
  public suspend fun installFromUrl(url: String): ExtensionManifest {
    if (!url.startsWith("http://") && !url.startsWith("https://")) {
      throw ExtensionInstallException("链接必须是 http(s) 地址")
    }
    val bytes = withContext(Dispatchers.IO) {
      downloadClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
        if (!response.isSuccessful) {
          throw ExtensionInstallException("下载失败: HTTP ${response.code}")
        }
        val source = response.body.source()
        source.request(JdrArchive.MAX_TOTAL_BYTES.toLong() + 1)
        if (source.buffer.size > JdrArchive.MAX_TOTAL_BYTES) {
          throw ExtensionInstallException("文件超过 ${JdrArchive.MAX_TOTAL_BYTES / 1024 / 1024}MB 上限")
        }
        source.readByteArray()
      }
    }
    return install(bytes)
  }

  /** Imports a package from a SAF content uri. */
  public suspend fun installFromUri(uri: android.net.Uri): ExtensionManifest {
    val bytes = withContext(Dispatchers.IO) {
      application.contentResolver.openInputStream(uri)?.use { input ->
        val buffer = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(64 * 1024)
        var total = 0
        while (true) {
          val read = input.read(chunk)
          if (read < 0) break
          total += read
          if (total > JdrArchive.MAX_TOTAL_BYTES) {
            throw ExtensionInstallException("文件超过 ${JdrArchive.MAX_TOTAL_BYTES / 1024 / 1024}MB 上限")
          }
          buffer.write(chunk, 0, read)
        }
        buffer.toByteArray()
      } ?: throw ExtensionInstallException("无法读取所选文件")
    }
    return install(bytes)
  }

  public suspend fun uninstall(manifestId: String) {
    mutationMutex.withLock {
      withContext(Dispatchers.IO) {
        packageDir(manifestId).deleteRecursively()
      }
      packagesStore.updateData { packages -> packages.filterNot { it.manifest.id == manifestId } }
    }
  }

  public suspend fun setSourceEnabled(
    sourceId: String,
    enabled: Boolean,
  ) {
    mutationMutex.withLock {
      packagesStore.updateData { packages ->
        packages.map { pkg ->
          if (pkg.manifest.sources.any { it.id == sourceId }) {
            pkg.copy(sourceEnabled = pkg.sourceEnabled + (sourceId to enabled))
          } else {
            pkg
          }
        }
      }
    }
  }

  /** Finds the installed package that provides [sourceId], if enabled. */
  public suspend fun packageFor(sourceId: String): InstalledExtensionPackage? {
    return installed().firstOrNull { pkg ->
      pkg.manifest.sources.any { it.id == sourceId } && pkg.isSourceEnabled(sourceId)
    }
  }
}
