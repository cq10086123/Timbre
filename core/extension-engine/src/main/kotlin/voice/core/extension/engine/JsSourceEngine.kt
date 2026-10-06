package voice.core.extension.engine

import com.dokar.quickjs.ExperimentalQuickJsApi
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.asyncFunction
import com.dokar.quickjs.binding.function
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer
import voice.core.extension.engine.crypto.CryptoOps
import java.io.Closeable
import java.util.Base64
import java.util.concurrent.Executors

/**
 * One QuickJS sandbox running one source script. All cross-boundary values
 * are strings (JSON/base64/hex) so nothing depends on QuickJS type mapping.
 *
 * The instance is confined to a single dedicated thread; stage calls are
 * serialized, and bounded only when the caller passes [invoke]'s timeout.
 */
@OptIn(ExperimentalQuickJsApi::class)
public class JsSourceEngine private constructor(
  public val sourceId: String,
  private val quickJs: QuickJs,
  private val dispatcher: ExecutorCoroutineDispatcher,
) : Closeable {

  /**
   * Calls one stage (`search` / `chapters` / `audio`) of the registered
   * source with [paramsJson] and returns the JSON-stringified result.
   */
  /**
   * Runs one stage of the source. The optional [timeoutMillis] caps the whole
   * invocation; production callers leave it unset so sources control timing
   * themselves via per-request `timeoutMs` options (OkHttp bounds each
   * request, tests pass an explicit cap to bound pathological scripts).
   */
  public suspend fun invoke(
    stage: String,
    paramsJson: String,
    timeoutMillis: Long? = null,
  ): String = withContext(dispatcher) {
    if (timeoutMillis != null) {
      withTimeout(timeoutMillis) {
        quickJs.evaluate<String>("await __invoke(${jsString(stage)}, ${jsString(paramsJson)})")
      }
    } else {
      quickJs.evaluate<String>("await __invoke(${jsString(stage)}, ${jsString(paramsJson)})")
    }
  }

  override fun close() {
    runQuietly { quickJs.close() }
    dispatcher.close()
  }

  public companion object {

    public const val DEFAULT_MEMORY_LIMIT_BYTES: Long = 64L * 1024 * 1024

    private val json = Json
    private const val PRELUDE_RESOURCE = "/voice/core/extension/engine/prelude.js"

    /**
     * Loads [script] into a fresh sandbox, verifying it registered a source
     * with the expected [sourceId]. Throws [SourceContractException] when the
     * script never registers or registers a different id.
     */
    public suspend fun create(
      sourceId: String,
      script: String,
      scriptName: String,
      http: SandboxHttp,
      log: (String) -> Unit = {},
      memoryLimitBytes: Long = DEFAULT_MEMORY_LIMIT_BYTES,
    ): JsSourceEngine {
      val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "js-source-$sourceId") }
      val dispatcher = executor.asCoroutineDispatcher()
      val quickJs = QuickJs.create(jobDispatcher = dispatcher)
      try {
        withContext(dispatcher) {
          quickJs.memoryLimit = memoryLimitBytes
          defineBindings(quickJs, http, log)
          quickJs.evaluate<Unit>(readPrelude(), "prelude.js")
          quickJs.evaluate<Unit>(script, scriptName)
          val info = quickJs.evaluate<String?>("__sourceInfo()")
            ?: throw SourceContractException("源脚本未调用 registerSource")
          val obj: JsonObject = json.parseToJsonElement(info).jsonObject
          val registeredId = obj["id"]?.jsonPrimitive?.contentOrNull ?: ""
          if (registeredId != sourceId) {
            throw SourceContractException("脚本注册的 id($registeredId) 与配置的($sourceId) 不一致")
          }
        }
      } catch (e: SourceContractException) {
        runQuietly { quickJs.close() }
        dispatcher.close()
        throw e
      } catch (e: Exception) {
        runQuietly { quickJs.close() }
        dispatcher.close()
        throw SourceContractException("加载源脚本失败: ${rootMessage(e)}", e)
      }
      return JsSourceEngine(sourceId, quickJs, dispatcher)
    }

    private inline fun runQuietly(block: () -> Unit) {
      try {
        block()
      } catch (_: Exception) {
      }
    }

    private fun defineBindings(
      quickJs: QuickJs,
      http: SandboxHttp,
      log: (String) -> Unit,
    ) {
      quickJs.function("__md5Hex") { args -> CryptoOps.md5Hex(args.string(0)) }
      quickJs.function("__sha256Hex") { args -> CryptoOps.sha256Hex(args.string(0)) }
      quickJs.function("__hmacSha256Hex") { args -> CryptoOps.hmacSha256Hex(args.string(0), args.string(1)) }
      quickJs.function("__aesEcbEncryptB64") { args ->
        b64(CryptoOps.aesEcbEncrypt(unb64(args.string(0)), unb64(args.string(1))))
      }
      quickJs.function("__aesEcbDecryptB64") { args ->
        b64(CryptoOps.aesEcbDecrypt(unb64(args.string(0)), unb64(args.string(1))))
      }
      quickJs.function("__aesGcmEncryptB64") { args ->
        b64(CryptoOps.aesGcmEncrypt(unb64(args.string(0)), unb64(args.string(1)), unb64(args.string(2)), null))
      }
      quickJs.function("__aesGcmDecryptB64") { args ->
        b64(CryptoOps.aesGcmDecrypt(unb64(args.string(0)), unb64(args.string(1)), unb64(args.string(2)), null))
      }
      quickJs.function("__chacha20Poly1305EncryptB64") { args ->
        b64(CryptoOps.chacha20Encrypt(unb64(args.string(0)), unb64(args.string(1)), unb64(args.string(2)), null))
      }
      quickJs.function("__chacha20Poly1305DecryptB64") { args ->
        b64(CryptoOps.chacha20Decrypt(unb64(args.string(0)), unb64(args.string(1)), unb64(args.string(2)), null))
      }
      quickJs.function("__sm4EcbEncryptB64") { args ->
        b64(CryptoOps.sm4EcbEncrypt(unb64(args.string(0)), unb64(args.string(1))))
      }
      quickJs.function("__sm4EcbDecryptB64") { args ->
        b64(CryptoOps.sm4EcbDecrypt(unb64(args.string(0)), unb64(args.string(1))))
      }
      quickJs.function("__randomBytesB64") { args -> b64(CryptoOps.randomBytes(args.int(0))) }
      quickJs.function("__timestamp") { _ -> System.currentTimeMillis() / 1000 }
      quickJs.function("__timestampMs") { _ -> System.currentTimeMillis() }
      quickJs.function("__urlEncode") { args -> CryptoOps.urlEncode(args.string(0)) }
      quickJs.function("__urlDecode") { args -> CryptoOps.urlDecode(args.string(0)) }
      quickJs.function("__log") { args -> log(args.string(0)) }
      quickJs.asyncFunction("__httpRequest") { args ->
        http.request(args.string(0), args.string(1), args.string(2))
      }
    }

    private fun readPrelude(): String {
      val stream = JsSourceEngine::class.java.getResourceAsStream(PRELUDE_RESOURCE)
        ?: throw IllegalStateException("缺少内嵌 prelude 资源 $PRELUDE_RESOURCE")
      return stream.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    private fun jsString(value: String): String = json.encodeToString(serializer<String>(), value)

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun unb64(value: String): ByteArray = Base64.getDecoder().decode(value)

    private fun rootMessage(e: Throwable): String = generateSequence(e as Throwable?) { it.cause }
      .filterNotNull()
      .lastOrNull { it.message.isNullOrBlank().not() }
      ?.message
      ?: e.toString()

    private fun Array<Any?>.string(index: Int): String = (getOrNull(index) as? String)
      ?: throw IllegalArgumentException("第${index + 1}个参数必须是字符串")

    private fun Array<Any?>.int(index: Int): Int = (getOrNull(index) as? Number)?.toInt()
      ?: throw IllegalArgumentException("第${index + 1}个参数必须是数字")
  }
}
