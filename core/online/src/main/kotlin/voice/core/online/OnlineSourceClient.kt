package voice.core.online

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** Thrown when the download site answers with an error or unexpected payload. */
public class OnlineSourceException(
  message: String,
  public val requiresRelogin: Boolean = false,
  cause: Throwable? = null,
) : Exception(message, cause)

@Serializable
internal data class LoginRequest(
  val code: String,
  val captchaId: String,
  val captcha: String,
  val client: String,
)

@Serializable
internal data class IntfBookRequest(@SerialName("book_id") val bookId: String)

@Serializable
internal data class AudioRequest(
  @SerialName("book_id") val bookId: String,
  @SerialName("chapter_id") val chapterId: String,
)

/**
 * Raw HTTP client for the audiobook download site ("喜马拉雅音频下载器").
 * Stateless: every method takes the base url and token explicitly, so the
 * token lifecycle (cache / silent re-login) can live in a separate facade.
 */
public class OnlineSourceClient internal constructor(
  private val okHttpClient: OkHttpClient,
  private val json: Json,
) {

  /**
   * Copy of the client with a bounded call timeout, used for lookups that run
   * while the player waits for audio. `newBuilder` keeps the connection pool
   * and the threads of the original client.
   */
  private val boundedClient: OkHttpClient by lazy {
    okHttpClient.newBuilder()
      .callTimeout(AUDIO_URL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
      .build()
  }

  /**
   * Card key login: fetches a captcha (the SVG carries its expected characters
   * in plain `<text>` elements, see [CaptchaExtractor]) and exchanges the card
   * key for a bearer token.
   */
  public suspend fun login(
    baseUrl: String,
    credential: String,
  ): String = withContext(Dispatchers.IO) {
    val base = normalize(baseUrl)
    val captcha = get(base + "/api/auth/captcha", CaptchaResponse.serializer(), token = null)
    val svg = captcha.svg.substringAfter("base64,", missingDelimiterValue = "")
    val answer = if (svg.isEmpty()) "" else CaptchaExtractor.extract(String(java.util.Base64.getDecoder().decode(svg)))
    val request = LoginRequest(
      code = credential,
      captchaId = captcha.captchaId,
      captcha = answer,
      client = CLIENT,
    )
    val response = post(
      base + "/api/auth/login",
      json.encodeToString(LoginRequest.serializer(), request),
      LoginResponse.serializer(),
      token = null,
    )
    if (!response.success || response.token.isBlank()) {
      throw OnlineSourceException("Card key login failed (wrong credential or captcha)")
    }
    response.token
  }

  /** Lists enabled search sources supported by the app. */
  public suspend fun interfaces(
    baseUrl: String,
    token: String,
  ): List<OnlineSourceInfo> {
    val response = get(normalize(baseUrl) + "/api/interfaces", InterfacesResponse.serializer(), token)
    // Older servers may still advertise the removed official source.
    return response.interfaces.filter {
      it.enabled && !it.name.trim().equals("official", ignoreCase = true)
    }
  }

  /** Search in one source (A, B, ...). */
  public suspend fun searchSource(
    baseUrl: String,
    token: String,
    source: String,
    keyword: String,
  ): List<OnlineSearchResult> {
    val url = normalize(baseUrl) + "/api/intf/" + enc(source) + "/search?keyword=" + enc(keyword)
    val response = get(url, IntfSearchResponse.serializer(), token)
    return response.results.map {
      OnlineSearchResult(
        source = source,
        bookId = it.albumId,
        title = it.title,
        author = it.author.orEmpty(),
        cover = it.cover.orEmpty(),
        intro = it.intro.orEmpty(),
        trackCount = it.trackCount,
      )
    }
  }

  /** Chapter list of a source book, keeping the server error message. */
  public suspend fun sourceAlbumListResponse(
    baseUrl: String,
    token: String,
    source: String,
    bookId: String,
  ): Triple<Boolean, String?, List<OnlineChapter>> {
    val body = json.encodeToString(IntfBookRequest.serializer(), IntfBookRequest(bookId = bookId))
    val response = post(
      normalize(baseUrl) + "/api/intf/" + enc(source) + "/album-list",
      body,
      IntfAlbumListResponse.serializer(),
      token,
    )
    return Triple(
      response.success,
      response.error,
      response.tracks.mapIndexed { index, t ->
        OnlineChapter(
          id = t.trackId,
          title = t.title,
          durationSeconds = t.duration,
          order = if (t.order > 0) t.order else index + 1,
        )
      },
    )
  }

  /** Full chapter list of a source book. */
  public suspend fun sourceAlbumList(
    baseUrl: String,
    token: String,
    source: String,
    bookId: String,
  ): List<OnlineChapter> {
    val body = json.encodeToString(IntfBookRequest.serializer(), IntfBookRequest(bookId = bookId))
    val response = post(
      normalize(baseUrl) + "/api/intf/" + enc(source) + "/album-list",
      body,
      IntfAlbumListResponse.serializer(),
      token,
    )
    return response.tracks.mapIndexed { index, t ->
      OnlineChapter(
        id = t.trackId,
        title = t.title,
        durationSeconds = t.duration,
        order = if (t.order > 0) t.order else index + 1,
      )
    }
  }

  /**
   * Resolves a direct streaming url for one chapter of a source
   * book. Returns null when the source cannot resolve the chapter.
   *
   * The lookup runs while the player waits for audio, so it is bounded: a
   * source that does not answer fails the start of the chapter instead of
   * holding the player until the client wide call timeout.
   */
  public suspend fun sourceAudio(
    baseUrl: String,
    token: String,
    source: String,
    bookId: String,
    chapterId: String,
  ): String? {
    val body = json.encodeToString(
      AudioRequest.serializer(),
      AudioRequest(bookId = bookId, chapterId = chapterId),
    )
    val response = post(
      normalize(baseUrl) + "/api/intf/" + enc(source) + "/audio",
      body,
      IntfAudioResponse.serializer(),
      token,
      client = boundedClient,
    )
    return if (response.success && response.url.isNotBlank()) response.url else null
  }

  private suspend fun <T> get(
    url: String,
    serializer: KSerializer<T>,
    token: String?,
  ): T = execute(request(url, token).build(), serializer)

  private suspend fun <T> post(
    url: String,
    body: String,
    serializer: KSerializer<T>,
    token: String?,
    client: OkHttpClient = okHttpClient,
  ): T = execute(
    request(url, token)
      .post(body.toRequestBody(JSON_MEDIA_TYPE))
      .build(),
    serializer,
    client,
  )

  private fun request(
    url: String,
    token: String?,
  ): Request.Builder {
    val builder = Request.Builder().url(url)
    if (token != null) builder.header("Authorization", "Bearer $token")
    return builder
  }

  private suspend fun <T> execute(
    request: Request,
    serializer: KSerializer<T>,
    client: OkHttpClient = okHttpClient,
  ): T = withContext(Dispatchers.IO) {
    client.newCall(request).execute().use { response ->
      val bodyString = response.body.string().orEmpty()
      if (response.code == 401) {
        throw OnlineSourceException("Unauthorized (token expired)", requiresRelogin = true)
      }
      if (!response.isSuccessful) {
        throw OnlineSourceException("HTTP ${response.code}: ${bodyString.take(200)}")
      }
      json.decodeFromString(serializer, bodyString)
    }
  }

  private fun normalize(baseUrl: String): String = baseUrl.trim().trimEnd('/')

  private fun enc(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

  public companion object {

    /**
     * Chapter listings regularly take 15+ seconds for
     * books with thousands of tracks - okhttp defaults (10s read) kill them.
     */
    internal fun defaultHttpClient(): OkHttpClient {
      return OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .callTimeout(90, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    }

    private const val CLIENT: String = "timbre"

    /**
     * How long a source may take to hand out a playable url. Long enough for a
     * slow interface, short enough that a dead one fails the chapter instead of
     * keeping the player in buffering.
     */
    private const val AUDIO_URL_TIMEOUT_MS = 20_000L
    private val JSON_MEDIA_TYPE = "application/json".toMediaType()

    /**
     * Creates a client with default networking - the DI graph can also inject
     * its own [OkHttpClient].
     */
    public fun create(okHttpClient: OkHttpClient = defaultHttpClient()): OnlineSourceClient {
      val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
      }
      return OnlineSourceClient(okHttpClient, json)
    }
  }
}
