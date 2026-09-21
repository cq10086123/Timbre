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
internal data class MainAlbumListRequest(@SerialName("album_id") val albumId: String)

@Serializable
internal data class IntfBookRequest(@SerialName("book_id") val bookId: String)

@Serializable
internal data class AudioRequest(
  @SerialName("book_id") val bookId: String,
  @SerialName("chapter_id") val chapterId: String,
)

@Serializable
internal data class BatchRequest(
  @SerialName("album_id") val albumId: String,
  @SerialName("start_episode") val startEpisode: Int,
  @SerialName("end_episode") val endEpisode: Int,
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

  /** Lists every search source the site currently exposes. */
  public suspend fun interfaces(
    baseUrl: String,
    token: String,
  ): List<OnlineSourceInfo> {
    val response = get(normalize(baseUrl) + "/api/interfaces", InterfacesResponse.serializer(), token)
    return response.interfaces.filter { it.enabled }
  }

  /** Search in the main (Ximalaya account backed) catalog. */
  public suspend fun searchMain(
    baseUrl: String,
    token: String,
    keyword: String,
  ): List<OnlineSearchResult> {
    val url = normalize(baseUrl) + "/api/search?keyword=" + enc(keyword)
    val response = get(url, MainSearchResponse.serializer(), token)
    return response.results.map {
      OnlineSearchResult(
        source = SOURCE_MAIN,
        bookId = it.albumId,
        title = it.title,
        author = it.author.orEmpty(),
        cover = it.cover.orEmpty(),
        intro = it.intro.orEmpty(),
        trackCount = it.tracks,
      )
    }
  }

  /** Search in one pluggable source (A, B, ...). */
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

  /** Full chapter list of a main-catalog book (cached by the caller). */
  public suspend fun mainAlbumList(
    baseUrl: String,
    token: String,
    bookId: String,
  ): List<OnlineChapter> {
    val body = json.encodeToString(MainAlbumListRequest.serializer(), MainAlbumListRequest(albumId = bookId))
    val response = post(
      normalize(baseUrl) + "/api/download/album-list",
      body,
      MainAlbumListResponse.serializer(),
      token,
    )
    return response.tracks.mapIndexed { index, t ->
      OnlineChapter(id = t.trackId, title = t.title, durationSeconds = t.duration, order = index + 1)
    }
  }

  /** Full chapter list of a pluggable-source book. */
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
   * Resolves a direct streaming url for one chapter of a pluggable-source
   * book. Returns null when the source cannot resolve the chapter.
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
    )
    return if (response.success && response.url.isNotBlank()) response.url else null
  }

  /** Asks the site to download episodes [startEpisode]..[endEpisode] of a main-catalog book. */
  public suspend fun submitBatch(
    baseUrl: String,
    token: String,
    bookId: String,
    startEpisode: Int,
    endEpisode: Int,
  ): String? {
    val body = json.encodeToString(
      BatchRequest.serializer(),
      BatchRequest(albumId = bookId, startEpisode = startEpisode, endEpisode = endEpisode),
    )
    val response = post(normalize(baseUrl) + "/api/download/batch", body, BatchSubmitResponse.serializer(), token)
    return response.taskId.ifBlank { null }
  }

  public suspend fun batchStatus(
    baseUrl: String,
    token: String,
    taskId: String,
  ): OnlineBatchStatus {
    val response = get(normalize(baseUrl) + "/api/download/batch/" + enc(taskId), BatchStatusResponse.serializer(), token)
    return OnlineBatchStatus(
      taskId = taskId,
      status = response.status,
      total = response.total,
      completed = response.completed,
    )
  }

  /** Lists albums already downloaded on the site (path is the streaming key). */
  public suspend fun downloadedAlbums(
    baseUrl: String,
    token: String,
  ): List<FilesAlbum> {
    val response = get(normalize(baseUrl) + "/api/files", FilesAlbumResponse.serializer(), token)
    return response.albums
  }

  /** Builds the streaming url (supports HTTP Range) for a downloaded file. */
  public fun fileUrl(
    baseUrl: String,
    path: String,
  ): String {
    val encoded = path.split("/")
      .joinToString("/") { segment -> enc(segment) }
    return normalize(baseUrl) + "/api/files/file/" + encoded
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
  ): T = execute(
    request(url, token)
      .post(body.toRequestBody(JSON_MEDIA_TYPE))
      .build(),
    serializer,
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
  ): T = withContext(Dispatchers.IO) {
    okHttpClient.newCall(request).execute().use { response ->
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
    /** The main (account backed) catalog, addressed through /api/search. */
    public const val SOURCE_MAIN: String = "main"
    private const val CLIENT: String = "timbre"
    private val JSON_MEDIA_TYPE = "application/json".toMediaType()

    /**
     * Creates a client with default networking - the DI graph can also inject
     * its own [OkHttpClient].
     */
    public fun create(okHttpClient: OkHttpClient = OkHttpClient.Builder().build()): OnlineSourceClient {
      val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
      }
      return OnlineSourceClient(okHttpClient, json)
    }
  }
}
