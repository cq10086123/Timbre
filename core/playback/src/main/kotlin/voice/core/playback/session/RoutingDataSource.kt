package voice.core.playback.session

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/**
 * Hands every DataSpec to the first matching factory and falls back to a
 * default for everything else. This is how `online://` playback coexists with
 * the webdav chain: online uris are routed before they can reach the webdav
 * data source, whose delegate cannot handle the scheme.
 */
internal class RoutingDataSource(
  private val routes: List<Pair<(Uri) -> Boolean, DataSource.Factory>>,
  private val fallback: DataSource.Factory,
) : DataSource {

  private val listeners = mutableListOf<TransferListener>()
  private var delegate: DataSource? = null

  override fun addTransferListener(transferListener: TransferListener) {
    listeners += transferListener
    delegate?.addTransferListener(transferListener)
  }

  override fun open(dataSpec: DataSpec): Long {
    close()
    val factory = routes.firstOrNull { it.first(dataSpec.uri) }?.second ?: fallback
    val source = factory.createDataSource()
    delegate = source
    listeners.forEach(source::addTransferListener)
    return source.open(dataSpec)
  }

  override fun read(
    target: ByteArray,
    offset: Int,
    length: Int,
  ): Int {
    return checkNotNull(delegate).read(target, offset, length)
  }

  override fun close() {
    delegate?.close()
    delegate = null
  }

  override fun getUri(): Uri? {
    return delegate?.uri
  }

  override fun getResponseHeaders(): Map<String, List<String>> {
    return delegate?.responseHeaders ?: emptyMap()
  }
}
