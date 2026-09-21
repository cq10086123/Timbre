package voice.core.online

import androidx.datastore.core.DataStore
import kotlinx.serialization.builtins.ListSerializer
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.Qualifier
import dev.zacsweers.metro.SingleIn

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
}
