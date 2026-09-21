package voice.core.online

import androidx.datastore.core.DataStore
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.Qualifier
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

@Qualifier
public annotation class OnlineSourceEnabledStore

@Qualifier
public annotation class OnlineSourceBaseUrlStore

@Qualifier
public annotation class OnlineSourceCredentialStore

@Qualifier
public annotation class OnlineSourceTokenStore

@Qualifier
public annotation class OnlineSourceBooksStore

/** The long-read OkHttpClient used for streaming audio playback. */
@Qualifier
public annotation class OnlineSourceStreamingClient

@ContributesTo(AppScope::class)
public interface OnlineSourceGraph {

  @Provides
  @SingleIn(AppScope::class)
  @OnlineSourceEnabledStore
  public fun onlineSourceEnabled(factory: OnlineSourceStoreFactory): DataStore<Boolean> {
    return factory.boolean(fileName = "onlineSourceEnabled", defaultValue = false)
  }

  @Provides
  @SingleIn(AppScope::class)
  @OnlineSourceBaseUrlStore
  public fun onlineSourceBaseUrl(factory: OnlineSourceStoreFactory): DataStore<String> {
    return factory.string(fileName = "onlineSourceBaseUrl", defaultValue = "")
  }

  @Provides
  @SingleIn(AppScope::class)
  @OnlineSourceCredentialStore
  public fun onlineSourceCredential(factory: OnlineSourceStoreFactory): DataStore<String> {
    return factory.string(fileName = "onlineSourceCredential", defaultValue = "")
  }

  @Provides
  @SingleIn(AppScope::class)
  @OnlineSourceTokenStore
  public fun onlineSourceToken(factory: OnlineSourceStoreFactory): DataStore<String> {
    return factory.string(fileName = "onlineSourceToken", defaultValue = "")
  }

  @Provides
  @SingleIn(AppScope::class)
  @OnlineSourceBooksStore
  public fun onlineSourceBooks(factory: OnlineSourceStoreFactory): DataStore<List<OnlineBook>> {
    return factory.create(
      serializer = ListSerializer(OnlineBook.serializer()),
      defaultValue = emptyList(),
      fileName = "onlineBooks",
    )
  }

  @Provides
  @SingleIn(AppScope::class)
  public fun onlineSourceClient(): OnlineSourceClient {
    return OnlineSourceClient.create()
  }

  /**
   * The client for streaming audio: no call timeout, because one call stays
   * open for a whole chapter (the api client's 90s call timeout would kill it
   * mid stream).
   */
  @Provides
  @SingleIn(AppScope::class)
  @OnlineSourceStreamingClient
  public fun onlineStreamingClient(): OkHttpClient {
    return OkHttpClient.Builder()
      .connectTimeout(15, TimeUnit.SECONDS)
      .readTimeout(60, TimeUnit.SECONDS)
      .writeTimeout(60, TimeUnit.SECONDS)
      .build()
  }

  @Provides
  @SingleIn(AppScope::class)
  public fun onlineDataSourceFactory(
    catalog: OnlinePlaybackCatalog,
    @OnlineSourceBaseUrlStore baseUrlStore: DataStore<String>,
    @OnlineSourceTokenStore tokenStore: DataStore<String>,
    @OnlineSourceStreamingClient streamingClient: OkHttpClient,
  ): OnlineDataSourceFactory {
    // open() runs on the exoplayer loading thread, where a blocking bridge is
    // fine (the webdav data source does its blocking network work there too)
    return OnlineDataSourceFactory(
      streamingClient,
      baseUrlProvider = { runBlocking { baseUrlStore.data.first() } },
      tokenProvider = { runBlocking { tokenStore.data.first() } },
      urlResolver = { ref ->
        runBlocking { catalog.resolveStreamUrl(ref) }
      },
      onDurationResolved = { ref, durationMs ->
        catalog.recordMeasuredDuration(ref.bookId, ref.chapterId, durationMs)
      },
    )
  }
}
