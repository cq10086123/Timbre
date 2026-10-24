package voice.core.update

/**
 * One rendered line of a markdown release notes body. The dialog applies a
 * light style: headings are emphasized, bullets get a uniform marker, and
 * inline markdown (links, images, bold) is reduced to its text.
 */
data class ReleaseNotesLine(
  val text: String,
  val isHeading: Boolean = false,
)

/**
 * Reduces a markdown release notes body to displayable lines: heading and
 * bullet markers become styles, links keep only their text, blank lines are
 * dropped. Unknown markdown is kept as plain text.
 */
fun formatReleaseNotes(markdown: String): List<ReleaseNotesLine> {
  return markdown
    .lines()
    .map { it.trim() }
    .filter { it.isNotEmpty() }
    .map { line ->
      val heading = line.startsWith("#")
      val bullet = !heading && (
        line.startsWith("- ") || line.startsWith("* ") || line.startsWith("+ ") ||
          line == "-" || line == "*" || line == "+"
        )
      val raw = when {
        heading -> line.trimStart('#')
        bullet -> if (line.length > 2) line.substring(2) else ""
        else -> line
      }
      val text = cleanInlineMarkdown(raw)
      when {
        text.isEmpty() -> null
        bullet -> ReleaseNotesLine("•  $text")
        else -> ReleaseNotesLine(text, isHeading = heading)
      }
    }
    .filterNotNull()
}

private fun cleanInlineMarkdown(text: String): String {
  var result = text
  // images ![alt](url) and links [text](url) collapse to their text
  result = result.replace(Regex("!\\[([^\\]]*)]\\([^)]*\\)"), "$1")
  result = result.replace(Regex("\\[([^\\]]*)]\\([^)]*\\)"), "$1")
  // bold and italic markers
  result = result.replace(Regex("\\*\\*([^*]+)\\*\\*"), "$1")
  result = result.replace(Regex("\\*([^*]+)\\*"), "$1")
  return result.trim()
}
