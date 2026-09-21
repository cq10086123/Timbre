package voice.core.online

import android.app.Application
import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import dev.zacsweers.metro.Inject
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Self-contained DataStore factory for the online source settings - mirrors
 * the webdav module's approach so the online feature shares no mutable state
 * with the rest of the app.
 */
@Inject
public class OnlineSourceStoreFactory internal constructor(private val context: Application) {

  private val json: Json = Json {
    ignoreUnknownKeys = true
  }

  internal fun <T> create(
    serializer: KSerializer<T>,
    defaultValue: T,
    fileName: String,
    migrations: List<DataMigration<T>> = emptyList(),
  ): DataStore<T> {
    return DataStoreFactory.create(
      migrations = migrations,
      serializer = OnlineSourceDataStoreSerializer(defaultValue, json, serializer),
    ) {
      File(context.applicationContext.filesDir, "datastore/$fileName")
    }
  }

  internal fun string(
    fileName: String,
    defaultValue: String,
  ): DataStore<String> {
    return create(
      serializer = String.serializer(),
      defaultValue = defaultValue,
      fileName = fileName,
    )
  }

  internal fun boolean(
    fileName: String,
    defaultValue: Boolean,
  ): DataStore<Boolean> {
    return create(
      serializer = Boolean.serializer(),
      defaultValue = defaultValue,
      fileName = fileName,
    )
  }
}
