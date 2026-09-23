package voice.features.bookOverview

/** Short human readable size for the cache dialogs, e.g. `1.4 GB`. */
internal fun formatBytes(bytes: Long): String {
  if (bytes <= 0L) return "0 B"
  val units = arrayOf("B", "KB", "MB", "GB")
  var value = bytes.toDouble()
  var unit = 0
  while (value >= 1024 && unit < units.lastIndex) {
    value /= 1024
    unit++
  }
  return if (unit == 0) {
    "${value.toLong()} ${units[unit]}"
  } else {
    "${(value * 10).toLong() / 10.0} ${units[unit]}"
  }
}
