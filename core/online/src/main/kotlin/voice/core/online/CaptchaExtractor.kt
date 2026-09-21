package voice.core.online

/**
 * The download site's captcha renders its characters as plain `<text>` elements
 * inside the SVG (distortion is done via transforms only), so the expected
 * answer can be read back deterministically - no OCR involved.
 */
public object CaptchaExtractor {

  private val textRegex = Regex("""<text[^>]*>([^<]*)</text>""")

  /** Concatenates every character found in `<text>` elements, in document order. */
  public fun extract(svg: String): String {
    return textRegex.findAll(svg)
      .joinToString(separator = "") { it.groupValues[1].trim() }
  }
}
