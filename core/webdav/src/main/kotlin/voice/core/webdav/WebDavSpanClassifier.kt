package voice.core.webdav

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * Knows which cached resources are speculative prefetch data and which book
 * they belong to, so the evictor can throw away prefetch data of inactive
 * books first. Otherwise the freshly prefetched data of a book that was left
 * halfway would keep evicting the data of the book that is actually playing
 * (prefetch pollution).
 */
@SingleIn(AppScope::class)
@Inject
public class WebDavSpanClassifier {

  private val lock = Any()
  private val speculativeByBook = HashMap<String, String>()

  @Volatile
  private var activeBookPrefix: String? = null

  public fun markActiveBook(bookUrl: String?) {
    activeBookPrefix = bookUrl?.trimEnd('/')
  }

  /** Marks [url] as speculative data prefetched for the book at [bookUrl]. */
  public fun markSpeculative(
    url: String,
    bookUrl: String,
  ) {
    synchronized(lock) {
      speculativeByBook[url] = bookUrl.trimEnd('/')
    }
  }

  /** The url was read by actual playback, so it is no longer speculative. */
  public fun markConsumed(url: String) {
    synchronized(lock) {
      speculativeByBook.remove(url)
    }
  }

  public fun forgetBook(bookUrl: String) {
    val prefix = bookUrl.trimEnd('/')
    synchronized(lock) {
      speculativeByBook.entries.removeAll { it.value == prefix }
    }
  }

  public fun isSpeculativeOfInactiveBook(url: String): Boolean {
    val book = synchronized(lock) {
      speculativeByBook[url] ?: return false
    }
    val active = activeBookPrefix ?: return true
    return book != active
  }
}
