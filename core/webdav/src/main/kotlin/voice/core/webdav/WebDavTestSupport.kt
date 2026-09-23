package voice.core.webdav

import android.app.Application
import androidx.datastore.core.DataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** A [DataStore] fake for tests. */
public class MemoryDataStore<T>(initial: T) : DataStore<T> {

  private val state = MutableStateFlow(initial)

  override val data: Flow<T> = state

  override suspend fun updateData(transform: suspend (T) -> T): T {
    val next = transform(state.value)
    state.value = next
    return next
  }
}

public fun testWebDavSecrets(): WebDavSecrets {
  return object : WebDavSecrets {
    override fun encrypt(plain: String): String = plain.reversed()
    override fun decrypt(encrypted: String): String? = encrypted.reversed()
  }
}

public fun testWebDavCredentialResolver(
  serversStore: DataStore<List<WebDavServer>> = MemoryDataStore(emptyList()),
  secrets: WebDavSecrets = testWebDavSecrets(),
): WebDavCredentialResolver {
  return WebDavCredentialResolver(
    serversStore = serversStore,
    secrets = secrets,
    scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
  )
}

/** A factory producing data sources that treat every configured server as matching. */
public fun testWebDavDataSourceFactory(application: Application): WebDavDataSourceFactory {
  return WebDavDataSourceFactory(
    resolver = testWebDavCredentialResolver(),
    client = WebDavClient(),
    classifier = WebDavSpanClassifier(),
    context = application,
  )
}
