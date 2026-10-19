package voice.core.extension.engine

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/** One source (interface) declared by a .jdr package manifest. */
@Serializable
public data class ExtensionSourceMeta(
  /** Globally unique source id; the app routes it as `jdr:<id>`. */
  public val id: String,
  public val name: String,
  /** Script file path inside the package, e.g. `itingshu.js`. */
  public val script: String,
  /** Subset of `search`, `chapters`, `audio`. */
  public val capabilities: List<String> = listOf(CAP_SEARCH, CAP_CHAPTERS, CAP_AUDIO),
) {
  public companion object {
    public const val CAP_SEARCH: String = "search"
    public const val CAP_CHAPTERS: String = "chapters"
    public const val CAP_AUDIO: String = "audio"
  }
}

/** manifest.json of a .jdr package. */
@Serializable
public data class ExtensionManifest(
  public val id: String,
  public val name: String,
  public val version: String,
  public val author: String = "",
  public val description: String = "",
  public val homepage: String = "",
  /** Trust broken TLS certificates for this package's http bridge. */
  public val allowInsecure: Boolean = false,
  public val sources: List<ExtensionSourceMeta> = emptyList(),
)

/** Thrown when a .jdr package is malformed. */
public class JdrFormatException public constructor(
  message: String,
  cause: Throwable? = null,
) : Exception(message, cause)

/**
 * A parsed .jdr package: a zip containing `manifest.json` plus the source
 * scripts it references. Parsing validates structure, sizes and ids.
 */
public class JdrArchive private constructor(
  public val manifest: ExtensionManifest,
  public val scripts: Map<String, String>,
) {

  public fun scriptFor(source: ExtensionSourceMeta): String =
    scripts[source.script] ?: throw JdrFormatException("包内缺少脚本文件 ${source.script}")

  public companion object {

    public const val MANIFEST_ENTRY: String = "manifest.json"
    public const val FILE_EXTENSION: String = ".jdr"
    public const val MAX_TOTAL_BYTES: Int = 20 * 1024 * 1024
    public const val MAX_SCRIPT_BYTES: Int = 5 * 1024 * 1024
    public const val MAX_ENTRIES: Int = 64

    /**
     * Cap for everything a package inflates to, not just for the zip itself.
     * Scripts are text and deflate roughly 4-8x, so a few hundred KB of zip
     * used to expand to the per-entry maximum times [MAX_ENTRIES]: 64 * 5MB =
     * 320MB held as bytes and again as strings. That is an out-of-memory on a
     * low end device from a package that looks perfectly small on disk. Real
     * sources are far below this: the limit only exists to kill such bombs.
     */
    public const val MAX_UNCOMPRESSED_BYTES: Int = 16 * 1024 * 1024

    private val MANIFEST_ID_REGEX = Regex("^[a-z0-9][a-z0-9._-]{1,63}$")
    private val SOURCE_ID_REGEX = Regex("^[a-z0-9][a-z0-9_-]{0,63}$")
    private val KNOWN_CAPABILITIES =
      setOf(ExtensionSourceMeta.CAP_SEARCH, ExtensionSourceMeta.CAP_CHAPTERS, ExtensionSourceMeta.CAP_AUDIO)

    private val json = Json { ignoreUnknownKeys = true }

    public fun parse(bytes: ByteArray): JdrArchive {
      if (bytes.isEmpty()) throw JdrFormatException("空文件")
      if (bytes.size > MAX_TOTAL_BYTES) throw JdrFormatException("包超过 ${MAX_TOTAL_BYTES / 1024 / 1024}MB 上限")

      var manifestJson: String? = null
      val files = HashMap<String, String>()
      var uncompressedBytes = 0
      ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
        var entry = zip.nextEntry
        var count = 0
        while (entry != null) {
          count++
          if (count > MAX_ENTRIES) throw JdrFormatException("包内文件数超过 $MAX_ENTRIES 上限")
          val name = entry.name
          if (name.startsWith("/") || name.contains("\\") || name.contains("..")) {
            throw JdrFormatException("非法的包内路径: $name")
          }
          if (entry.isDirectory) {
            entry = zip.nextEntry
            continue
          }
          // bounded read: never allocate more than the cap for one entry
          val content = readBounded(zip, MAX_SCRIPT_BYTES, name)
          uncompressedBytes += content.size
          if (uncompressedBytes > MAX_UNCOMPRESSED_BYTES) {
            throw JdrFormatException("包解压后超过 ${MAX_UNCOMPRESSED_BYTES / 1024 / 1024}MB 上限")
          }
          if (name == MANIFEST_ENTRY) {
            manifestJson = content.toString(Charsets.UTF_8)
          } else {
            files[name] = content.toString(Charsets.UTF_8)
          }
          entry = zip.nextEntry
        }
      }

      val rawManifest = manifestJson ?: throw JdrFormatException("包内缺少 manifest.json")
      val manifest = try {
        json.decodeFromString<ExtensionManifest>(rawManifest)
      } catch (e: Exception) {
        throw JdrFormatException("manifest.json 解析失败: ${e.message}", e)
      }
      validateManifest(manifest)
      manifest.sources.forEach { source ->
        if (!files.containsKey(source.script)) {
          throw JdrFormatException("manifest 引用的脚本不存在: ${source.script}")
        }
        if (files.getValue(source.script).isBlank()) {
          throw JdrFormatException("脚本文件为空: ${source.script}")
        }
      }
      return JdrArchive(manifest, files)
    }

    private fun readBounded(
      zip: ZipInputStream,
      maxBytes: Int,
      name: String,
    ): ByteArray {
      val buffer = java.io.ByteArrayOutputStream(64 * 1024)
      val chunk = ByteArray(64 * 1024)
      var total = 0
      while (true) {
        val read = zip.read(chunk)
        if (read < 0) break
        total += read
        if (total > maxBytes) throw JdrFormatException("文件超过大小上限: $name")
        buffer.write(chunk, 0, read)
      }
      return buffer.toByteArray()
    }

    private fun validateManifest(manifest: ExtensionManifest) {
      if (!MANIFEST_ID_REGEX.matches(manifest.id)) {
        throw JdrFormatException("manifest.id 非法（需小写字母/数字/._-，3-64 位）: ${manifest.id}")
      }
      if (manifest.name.isBlank()) throw JdrFormatException("manifest.name 不能为空")
      if (manifest.version.isBlank()) throw JdrFormatException("manifest.version 不能为空")
      if (manifest.sources.isEmpty()) throw JdrFormatException("manifest.sources 不能为空")
      val ids = HashSet<String>()
      manifest.sources.forEach { source ->
        if (!SOURCE_ID_REGEX.matches(source.id)) {
          throw JdrFormatException("源 id 非法: ${source.id}")
        }
        if (!ids.add(source.id)) {
          throw JdrFormatException("源 id 重复: ${source.id}")
        }
        if (source.name.isBlank()) throw JdrFormatException("源 ${source.id} 的 name 不能为空")
        if (source.script.isBlank()) throw JdrFormatException("源 ${source.id} 的 script 不能为空")
        val unknown = source.capabilities - KNOWN_CAPABILITIES
        if (unknown.isNotEmpty()) {
          throw JdrFormatException("源 ${source.id} 含未知能力: $unknown")
        }
      }
    }
  }
}
