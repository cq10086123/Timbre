package voice.core.webdav

import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan

/**
 * Tiered LRU eviction:
 * 1. speculative prefetch data of books that are not currently playing,
 * 2. everything else by last touch (playing data is touched continuously and
 *    therefore naturally protected).
 */
internal class WebDavCacheEvictor(
  private val maxBytes: () -> Long,
  private val classifier: WebDavSpanClassifier,
) : CacheEvictor {

  override fun requiresCacheSpanTouches(): Boolean = true

  override fun onCacheInitialized() = Unit

  override fun onSpanAdded(
    cache: Cache,
    span: CacheSpan,
  ) {
    evictIfNeeded(cache)
  }

  override fun onSpanRemoved(
    cache: Cache,
    span: CacheSpan,
  ) = Unit

  override fun onSpanTouched(
    cache: Cache,
    span: CacheSpan,
    touchTimestamp: Long,
  ) = Unit

  /** Runs a full eviction round, e.g. after the limit was lowered in the settings. */
  fun evictIfNeeded(cache: Cache) {
    val max = maxBytes().coerceAtLeast(0)
    if (max <= 0L) return
    var overBy = cache.cacheSpace - max
    if (overBy <= 0L) return

    val spans = cache.keys.flatMap { key -> cache.getCachedSpans(key) }
    for (span in evictionOrder(spans, classifier::isSpeculativeOfInactiveBook)) {
      if (overBy <= 0L) break
      val before = cache.cacheSpace
      cache.removeSpan(span)
      val removed = before - cache.cacheSpace
      if (removed > 0) {
        overBy -= removed
      }
    }
  }
}

/** Speculative data of inactive books first, then plain LRU. */
internal fun evictionOrder(
  spans: List<CacheSpan>,
  isSpeculativeOfInactiveBook: (String) -> Boolean,
): List<CacheSpan> {
  return spans.sortedWith(
    compareBy(
      { span -> if (isSpeculativeOfInactiveBook(span.key)) 0 else 1 },
      { span -> span.lastTouchTimestamp },
    ),
  )
}
