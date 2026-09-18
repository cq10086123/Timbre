package voice.core.webdav

import android.app.Application
import androidx.datastore.core.DataStore
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

/** A factory producing data sources that treat every configured server as matching. */
public fun testWebDavDataSourceFactory(application: Application): WebDavDataSourceFactory {
  val resolver = WebDavCredentialResolver(
    serversStore = MemoryDataStore(emptyList()),
    secrets = testWebDavSecrets(),
  )
  return WebDavDataSourceFactory(
    resolver = resolver,
    client = WebDavClient(),
    classifier = WebDavSpanClassifier(),
    context = application,
  )
}
