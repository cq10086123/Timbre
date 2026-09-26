package voice.core.source.runtime

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.asyncFunction
import com.dokar.quickjs.binding.function
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import voice.core.logging.api.Logger

/** Thrown when a plugin source call fails inside the javascript runtime. */
public class SourceScriptException(message: String, cause: Throwable? = null) :
  Exception(message, cause)

/** Log sink for `api.log` calls, capped so a noisy plugin cannot flood. */
public fun interface SourceLogSink {
  public fun log(message: String)
}

/**
 * One QuickJS instance running one jdr bundle. The bundle registers sources
 * via `registerSource({id, name}, impl)`; each call goes through
 * [callSource], which awaits the (possibly async) javascript function and
 * returns its `JSON.stringify`-ed result.
 *
 * The host api available to the bundle:
 * - `api.http.request({url, method, headers, body})` -> `{status, headers, body, url}`
 * - `api.log(...)` / `console.log(...)` -> app log
 */
public class QuickJsSourceRuntime private constructor(
  private val quickJs: QuickJs,
) {

  private val mutex = Mutex()
  private val json = Json { ignoreUnknownKeys = true }

  /** The sources the bundle registered, in registration order. */
  public suspend fun registeredSources(): List<RegisteredSourceMeta> = mutex.withLock {
    val raw = quickJs.evaluate<String>(
      "JSON.stringify(Object.keys(__timbreSources).map(function (k) { return __timbreSources[k].meta; }))",
    )
    json.decodeFromString(ListSerializer(RegisteredSourceMeta.serializer()), raw)
  }

  /**
   * Calls one method on one registered source. [args] are already json
   * encoded values; the result is the json string the source returned.
   */
  public suspend fun call(
    sourceId: String,
    method: String,
    args: List<String>,
  ): String = mutex.withLock {
    try {
      withTimeout(CALL_TIMEOUT_MS) {
        val code = "__timbreCall(${JsonPrimitive(sourceId)}, ${JsonPrimitive(method)}, ${JsonPrimitive(argsJson(args))})"
        quickJs.evaluate<String>(code)
      }
    } catch (e: TimeoutCancellationException) {
      throw SourceScriptException("source call timed out after ${CALL_TIMEOUT_MS}ms: $sourceId.$method", e)
    } catch (e: SourceScriptException) {
      throw e
    } catch (e: Exception) {
      throw SourceScriptException("$sourceId.$method failed: ${e.message}", e)
    }
  }

  private fun argsJson(args: List<String>): String = args.joinToString(",", "[", "]")

  public fun close() {
    quickJs.close()
  }

  public companion object {

    /** Upper bound for one source call, covering its http requests. */
    public const val CALL_TIMEOUT_MS: Long = 30_000L
    private const val HTTP_TIMEOUT_MS: Long = 20_000L

    /**
     * Creates a runtime, evaluates the prelude plus [bundle] and waits for
     * the registrations to settle. Throws [SourceScriptException] when the
     * bundle is broken or registers nothing sensible.
     */
    public suspend fun create(
      bundle: String,
      httpClient: OkHttpClient,
      logSink: SourceLogSink,
    ): QuickJsSourceRuntime {
      var logCount = 0
      return try {
        val quickJs = QuickJs.create(jobDispatcher = Dispatchers.Default)
        quickJs.evaluationTimeoutMillis = 15_000L

        quickJs.asyncFunction("__timbreHttp") { args ->
          val options = args.firstOrNull() as? Map<*, *>
            ?: throw IllegalArgumentException("api.http.request expects an options object")
          httpRequest(httpClient, options)
        }
        quickJs.function("__timbreLog") { args ->
          if (logCount < 200) {
            logCount++
            logSink.log(args.filterNotNull().joinToString(" ") { it.toString() })
          }
          null
        }
        quickJs.evaluate<Unit>(prelude() + "\n" + bundle, filename = "bundle.js")
        QuickJsSourceRuntime(quickJs)
      } catch (e: SourceScriptException) {
        throw e
      } catch (e: Exception) {
        throw SourceScriptException("bundle failed to load: ${e.message}", e)
      }
    }

    private suspend fun httpRequest(httpClient: OkHttpClient, options: Map<*, *>): Map<String, Any?> {
      val url = (options["url"] as? String)?.trim().orEmpty()
      val httpUrl = url.toHttpUrlOrNull()
      if (httpUrl == null || (httpUrl.scheme != "http" && httpUrl.scheme != "https")) {
        throw IllegalArgumentException("api.http.request: invalid url: $url")
      }
      val method = ((options["method"] as? String) ?: "GET").uppercase()
      val builder = Request.Builder().url(httpUrl)
      (options["headers"] as? Map<*, *>)?.forEach { (k, v) ->
        val name = k?.toString() ?: return@forEach
        val value = v?.toString() ?: return@forEach
        if (name.isNotBlank() && value.isNotBlank()) builder.header(name, value)
      }
      val body = (options["body"] as? String)
      when (method) {
        "GET", "HEAD" -> builder.method(method, null)
        "POST", "PUT", "PATCH", "DELETE" -> builder.method(
          method,
          (body ?: "").toRequestBody("text/plain; charset=utf-8".toMediaType()),
        )
        else -> throw IllegalArgumentException("api.http.request: unsupported method $method")
      }
      return withContext(Dispatchers.IO) {
        httpClient.newCall(builder.build()).execute().use { response ->
          LinkedHashMap<String, Any?>().apply {
            put("status", response.code)
            put("url", response.request.url.toString())
            put(
              "headers",
              response.headers.toMultimap().mapValues { (_, values) -> values.joinToString(", ") },
            )
            put("body", response.body.string())
          }
        }
      }
    }

    private fun prelude(): String = """
      var __timbreSources = Object.create(null);
      var __timbreLogCount = 0;
      function registerSource(meta, impl) {
        if (meta === null || typeof meta !== 'object' || typeof meta.id !== 'string' || !meta.id) {
          throw new Error('registerSource: meta.id (string) is required');
        }
        if (impl === null || typeof impl !== 'object') {
          throw new Error('registerSource: impl object is required');
        }
        var name = typeof meta.name === 'string' && meta.name ? meta.name : meta.id;
        __timbreSources[meta.id] = { meta: { id: meta.id, name: name }, impl: impl };
      }
      async function __timbreCall(sourceId, method, argsJson) {
        var entry = __timbreSources[sourceId];
        if (!entry) throw new Error('source not registered: ' + sourceId);
        var fn = entry.impl[method];
        if (typeof fn !== 'function') throw new Error('source ' + sourceId + ' does not implement ' + method);
        var args = JSON.parse(argsJson);
        var result = await fn.apply(entry.impl, args);
        return JSON.stringify(result === undefined ? null : result);
      }
      var api = {
        log: function () {
          if (__timbreLogCount < 200) {
            __timbreLogCount++;
            var parts = new Array(arguments.length);
            for (var i = 0; i < arguments.length; i++) parts[i] = String(arguments[i]);
            __timbreLog(parts.join(' '));
          }
        },
        http: {
          request: function (options) { return __timbreHttp(options); }
        }
      };
      var console = {
        log: function () { api.log.apply(null, arguments); },
        info: function () { api.log.apply(null, arguments); },
        warn: function () { api.log.apply(null, arguments); },
        error: function () { api.log.apply(null, arguments); }
      };
    """
  }
}

/** Metadata of a source registered by a bundle. */
@Serializable
public data class RegisteredSourceMeta(
  public val id: String,
  public val name: String,
)
