package voice.core.webdav

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.Qualifier
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream

@Qualifier
public annotation class WebDavServersStore

@Qualifier
public annotation class WebDavBookSourcesStore

@Qualifier
public annotation class WebDavCacheSettingsStore

internal class WebDavDataStoreSerializer<T>(
  override val defaultValue: T,
  private val json: Json,
  private val serializer: KSerializer<T>,
) : Serializer<T> {

  @OptIn(ExperimentalSerializationApi::class)
  override suspend fun readFrom(input: InputStream): T = json.decodeFromStream(serializer, input)

  @OptIn(ExperimentalSerializationApi::class)
  override suspend fun writeTo(
    t: T,
    output: OutputStream,
  ) {
    json.encodeToStream(serializer, t, output)
  }
}

@Inject
public class WebDavStoreFactory(private val context: Application) {

  private val json: Json = Json {
    ignoreUnknownKeys = true
  }

  internal fun <T> create(
    serializer: KSerializer<T>,
    defaultValue: T,
    fileName: String,
  ): DataStore<T> {
    return DataStoreFactory.create(
      serializer = WebDavDataStoreSerializer(defaultValue, json, serializer),
      scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    ) {
      File(context.applicationContext.filesDir, "datastore/$fileName")
    }
  }
}

@ContributesTo(AppScope::class)
public interface WebDavGraph {

  @Provides
  @SingleIn(AppScope::class)
  @WebDavServersStore
  public fun webDavServersStore(factory: WebDavStoreFactory): DataStore<List<WebDavServer>> {
    return factory.create(
      serializer = ListSerializer(WebDavServer.serializer()),
      defaultValue = emptyList(),
      fileName = "webDavServers",
    )
  }

  @Provides
  @SingleIn(AppScope::class)
  @WebDavBookSourcesStore
  public fun webDavBookSourcesStore(factory: WebDavStoreFactory): DataStore<List<WebDavBookSource>> {
    return factory.create(
      serializer = ListSerializer(WebDavBookSource.serializer()),
      defaultValue = emptyList(),
      fileName = "webDavBookSources",
    )
  }

  @Provides
  @SingleIn(AppScope::class)
  @WebDavCacheSettingsStore
  public fun webDavCacheSettingsStore(factory: WebDavStoreFactory): DataStore<WebDavCacheSettings> {
    return factory.create(
      serializer = WebDavCacheSettings.serializer(),
      defaultValue = WebDavCacheSettings(),
      fileName = "webDavCacheSettings",
    )
  }
}
