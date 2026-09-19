package voice.core.scanner

import java.util.Locale

/**
 * Formats BitmapFactory can decode. Anything else would only end up as a
 * broken cover file, so it is treated as if no picture was there.
 */
internal val supportedImageExtensions = setOf(
  "avif",
  "bmp",
  "gif",
  "heic",
  "heif",
  "ico",
  "jfif",
  "jpe",
  "jpeg",
  "jpg",
  "png",
  "wbmp",
  "webp",
)

/**
 * Picture check for files that only expose a name and no mime type, e.g.
 * WebDAV resources.
 */
internal fun String.isSupportedImageFileName(): Boolean {
  return substringAfterLast('.', missingDelimiterValue = "").lowercase(Locale.US) in supportedImageExtensions
}
