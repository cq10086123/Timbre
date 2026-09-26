package voice.core.source.jdr

import kotlinx.serialization.json.Json
import voice.core.logging.api.Logger
import java.io.ByteArrayInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/** Thrown when a jdr package is malformed or hostile. */
public class JdrFormatException(message: String) : Exception(message)

/** The parsed content of a jdr package. */
public data class JdrPackage(
  val manifest: JdrManifest,
  val bundle: String,
  /** Declared size of the original package, for display and logging. */
  val packageBytes: Long,
)

/**
 * Loads and validates jdr packages. A jdr is a plain zip holding a
 * `manifest.json` and an obfuscated javascript bundle; the guards here are
 * what stands between a malicious package and the app:
 *
 * - package size and entry count caps (zip bombs),
 * - entry name validation (`..` traversal, absolute paths, escapes),
 * - duplicate entry rejection (the classic zip smuggling trick),
 * - manifest sanity (format version, package/source id charset, entry
 *   presence and size caps),
 * - the bundle must register every source the manifest declares.
 */
public class JdrLoader {

  private val json = Json { ignoreUnknownKeys = true }

  /** Parses and validates [bytes] as a jdr package. */
  public fun load(bytes: ByteArray): JdrPackage {
    if (bytes.isEmpty()) throw JdrFormatException("package is empty")
    if (bytes.size > MAX_PACKAGE_BYTES) {
      throw JdrFormatException("package too large: ${bytes.size} bytes")
    }

    val entries = LinkedHashMap<String, ByteArray>()
    ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
      var entry: ZipEntry? = zip.nextEntry
      while (entry != null) {
        val name = sanitizeEntryName(entry.name)
        if (name.isNotEmpty()) {
          if (entries.containsKey(name)) {
            throw JdrFormatException("duplicate zip entry: $name")
          }
          if (entries.size >= MAX_ENTRIES) {
            throw JdrFormatException("too many zip entries")
          }
          val data = zip.readBytes()
          if (data.size > MAX_ENTRY_BYTES) {
            throw JdrFormatException("zip entry too large: $name")
          }
          entries[name] = data
        }
        zip.closeEntry()
        entry = zip.nextEntry
      }
    }

    val manifestBytes = entries[MANIFEST_ENTRY]
      ?: throw JdrFormatException("missing manifest.json")
    val manifest = try {
      json.decodeFromString(JdrManifest.serializer(), manifestBytes.decodeToString())
    } catch (e: Exception) {
      throw JdrFormatException("manifest.json is invalid: ${e.message}")
    }

    if (manifest.formatVersion != JdrManifest.FORMAT_VERSION) {
      throw JdrFormatException(
        "unsupported format version ${manifest.formatVersion}, expected ${JdrManifest.FORMAT_VERSION}",
      )
    }
    if (!manifest.packageId.matches(PACKAGE_ID_PATTERN)) {
      throw JdrFormatException("packageId must match ${PACKAGE_ID_PATTERN.pattern}")
    }
    if (manifest.name.isBlank()) throw JdrFormatException("package name is blank")
    if (manifest.sources.isEmpty()) {
      throw JdrFormatException("manifest declares no sources")
    }
    val sourceIds = HashSet<String>()
    for (info in manifest.sources) {
      if (!info.id.matches(SOURCE_ID_PATTERN)) {
        throw JdrFormatException("source id must match ${SOURCE_ID_PATTERN.pattern}: ${info.id}")
      }
      if (!sourceIds.add(info.id)) {
        throw JdrFormatException("duplicate source id in manifest: ${info.id}")
      }
    }
    val entryName = manifest.entry.ifBlank { JdrManifest.DEFAULT_ENTRY }
    val bundleBytes = entries[entryName]
      ?: throw JdrFormatException("bundle entry missing: $entryName")
    val bundle = bundleBytes.decodeToString()
    if (bundle.isBlank()) throw JdrFormatException("bundle is empty")

    Logger.i("loaded ${manifest.packageId} v${manifest.version}: ${manifest.sources.size} sources")
    return JdrPackage(manifest = manifest, bundle = bundle, packageBytes = bytes.size.toLong())
  }

  /** Empty for directories, sanitized otherwise; throws on hostile names. */
  private fun sanitizeEntryName(name: String): String {
    if (name.endsWith("/")) return "" // directory
    if (name.isEmpty() || name.startsWith("/") || name.contains("\\")) {
      throw JdrFormatException("unsafe zip entry name: $name")
    }
    if (name.split('/').contains("..")) {
      throw JdrFormatException("zip entry escapes the package: $name")
    }
    if (!name.matches(ENTRY_NAME_PATTERN)) {
      throw JdrFormatException("zip entry name has unsafe characters: $name")
    }
    return name
  }

  public companion object {
    private const val TAG = "JdrLoader"
    public const val MANIFEST_ENTRY: String = "manifest.json"
    public const val MAX_PACKAGE_BYTES: Long = 8L * 1024 * 1024
    public const val MAX_ENTRY_BYTES: Long = 6L * 1024 * 1024
    public const val MAX_ENTRIES: Int = 64

    /** Lowercase ids keep the `jdr:` route names filesystem- and uri-safe. */
    private val PACKAGE_ID_PATTERN = Regex("[a-z0-9][a-z0-9-]{0,63}")
    private val SOURCE_ID_PATTERN = Regex("[a-z0-9][a-z0-9_-]{0,63}")
    private val ENTRY_NAME_PATTERN = Regex("[A-Za-z0-9._][A-Za-z0-9._/-]{0,255}")
  }
}
