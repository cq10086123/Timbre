package voice.core.online

import android.app.Application
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.datastore.core.DataStore
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.Qualifier
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

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

/** Unfinished manual cache jobs, so a killed process does not lose them. */
@Qualifier
public annotation class OnlineCacheJobsStore

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

  @Provides
  @SingleIn(AppScope::class)
  public fun onlineChapterFileCache(application: Application): OnlineChapterFileCache {
    return OnlineChapterFileCache(File(application.filesDir, OnlineChapterFileCache.CACHE_DIR))
  }

  @Provides
  @SingleIn(AppScope::class)
  @OnlineCacheJobsStore
  public fun onlineCacheJobs(factory: OnlineSourceStoreFactory): DataStore<List<OnlineCacheJob>> {
    return factory.create(
      serializer = ListSerializer(OnlineCacheJob.serializer()),
      defaultValue = emptyList(),
      fileName = "onlineCacheJobs",
    )
  }

  /**
   * Metered means "the next download would cost the user money".
   *
   * Offline does not: there is no data volume to spend, the job fails on its
   * own and is retried later, and asking for mobile data permission while
   * offline would only puzzle the user. The prefetch scheduler reads the plain
   * `isActiveNetworkMetered` instead, because for it "offline" and "metered"
   * both simply mean pause.
   *
   * Everything that cannot be answered (no connectivity service, capabilities
   * for the active network unknown) counts as metered: for a job that spends
   * the user's data volume, asking once too often beats asking once too
   * little.
   */
  @Provides
  @SingleIn(AppScope::class)
  public fun meteredNetworkChecker(application: Application): MeteredNetworkChecker {
    return MeteredNetworkChecker {
      val connectivity = application.getSystemService(ConnectivityManager::class.java)
      val active = connectivity?.activeNetwork
      when {
        connectivity == null -> true
        active == null -> false
        else -> connectivity.getNetworkCapabilities(active)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) != true
      }
    }
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
    fileCache: OnlineChapterFileCache,
    @OnlineSourceBaseUrlStore baseUrlStore: DataStore<String>,
    @OnlineSourceTokenStore tokenStore: DataStore<String>,
    @OnlineSourceStreamingClient streamingClient: OkHttpClient,
    scope: CoroutineScope,
  ): OnlineDataSourceFactory {
    // open() runs on the exoplayer loading thread. Keep baseUrl/token warm so
    // the common path is a lock-free read; fall back to a one-shot blocking
    // read only before the first collector emission.
    val baseUrl = AtomicReference<String?>(null)
    val token = AtomicReference<String?>(null)
    scope.launch {
      baseUrlStore.data.collect { baseUrl.set(it) }
    }
    scope.launch {
      tokenStore.data.collect { token.set(it) }
    }
    return OnlineDataSourceFactory(
      streamingClient,
      baseUrlProvider = {
        baseUrl.get() ?: runBlocking { baseUrlStore.data.first() }.also { baseUrl.set(it) }
      },
      tokenProvider = {
        token.get() ?: runBlocking { tokenStore.data.first() }.also { token.set(it) }
      },
      urlResolver = { ref ->
        runBlocking { catalog.resolveStreamUrl(ref) }
      },
      // a rejected url (expired direct link) is dropped, so the retry inside
      // the data source asks the source for a fresh one
      onUrlRejected = { ref -> catalog.invalidateStreamUrl(ref) },
      onDurationResolved = { ref, durationMs ->
        catalog.recordMeasuredDuration(ref.source, ref.bookId, ref.chapterId, durationMs)
      },
      hasMeasuredDuration = { ref ->
        catalog.measuredDurationMs(ref.source, ref.bookId, ref.chapterId) != null
      },
      fileCache = fileCache,
    )
  }
}
