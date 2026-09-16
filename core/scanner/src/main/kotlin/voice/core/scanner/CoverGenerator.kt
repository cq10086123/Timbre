package voice.core.scanner

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import androidx.core.graphics.createBitmap
import dev.zacsweers.metro.Inject

/**
 * Draws a cover for a book that has no artwork anywhere.
 *
 * The colours and the glyph are derived from the book name, so the same book
 * always gets the same cover and the shelf doesn't shuffle on every scan.
 *
 * It is drawn on the device on purpose: the privacy note promises that the
 * only network call is a cover search the user starts themselves, and a
 * placeholder must not fetch anything. It also has to work without a
 * connection, which is exactly when a placeholder is wanted.
 */
@Inject
internal class CoverGenerator {

  fun create(
    bookName: String,
    size: Int = GENERATED_COVER_SIZE,
  ): Bitmap {
    val hue = bookName.fold(0) { acc, c -> acc * 31 + c.code }.mod(360).toFloat()
    val startColor = Color.HSVToColor(floatArrayOf(hue, 0.45f, 0.58f))
    val endColor = Color.HSVToColor(floatArrayOf((hue + 36f) % 360f, 0.58f, 0.30f))

    val bitmap = createBitmap(size, size)
    val canvas = Canvas(bitmap)
    val background = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      shader = LinearGradient(
        0f,
        0f,
        size.toFloat(),
        size.toFloat(),
        startColor,
        endColor,
        Shader.TileMode.CLAMP,
      )
    }
    canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), background)

    val initial = bookName.trim().firstOrNull { !it.isWhitespace() } ?: return bitmap
    val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.WHITE
      typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
      textSize = size * 0.42f
      textAlign = Paint.Align.CENTER
    }
    // a baseline sits below the visual centre, so centre the glyph box instead
    val metrics = glyph.fontMetrics
    val centerY = size / 2f - (metrics.ascent + metrics.descent) / 2f
    canvas.drawText(initial.toString(), size / 2f, centerY, glyph)
    return bitmap
  }
}

private const val GENERATED_COVER_SIZE = 512
