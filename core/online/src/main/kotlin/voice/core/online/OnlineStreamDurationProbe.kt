package voice.core.online

import kotlin.math.ceil

/**
 * Estimates the real duration of a remote mp3 stream from its size and the
 * first frame headers. Used to correct chapters whose duration the source
 * does not report, so the player never clips playback to a placeholder.
 */
public object OnlineStreamDurationProbe {

  private val MPEG1_L3_BITRATES_KBPS = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)
  private val MPEG2_L3_BITRATES_KBPS = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160)
  private val MPEG1_SAMPLE_RATES = intArrayOf(44_100, 48_000, 32_000)
  private val MPEG2_SAMPLE_RATES = intArrayOf(22_050, 24_000, 16_000)
  private val MPEG25_SAMPLE_RATES = intArrayOf(11_025, 12_000, 8_000)

  /**
   * Estimates the duration in ms.
   *
   * @param contentLength total stream size in bytes (from Content-Length)
   * @param head the first bytes of the stream (id3 tag + audio frames)
   */
  public fun estimateDurationMs(
    contentLength: Long,
    head: ByteArray,
  ): Long? {
    if (contentLength <= 0L || head.size < 8) return null
    var offset = skipId3(head) ?: return null
    val frame = findFrameHeader(head, offset) ?: return null

    // vbr streams carry a Xing/Info header with an exact frame count
    val xingFrames = findXingFrameCount(head, frame.offset, frame.versionMpeg1)
    if (xingFrames != null && frame.sampleRate > 0) {
      return xingFrames * frame.samplesPerFrame * 1_000L / frame.sampleRate
    }
    if (frame.bitrateKbps <= 0) return null
    return contentLength * 8L / frame.bitrateKbps
  }

  /** Returns the byte offset just after an ID3v2 tag, or null when absent/malformed. */
  private fun skipId3(head: ByteArray): Int? {
    if (head.size < 10 || head[0] != 'I'.code.toByte() || head[1] != 'D'.code.toByte() || head[2] != '3'.code.toByte()) {
      return 0
    }
    val size = ((head[6].toInt() and 0x7F) shl 21) or
      ((head[7].toInt() and 0x7F) shl 14) or
      ((head[8].toInt() and 0x7F) shl 7) or
      (head[9].toInt() and 0x7F)
    val end = 10 + size
    return if (end < head.size) end else null
  }

  /** Finds the first valid mp3 frame header and decodes its fields. */
  private fun findFrameHeader(
    head: ByteArray,
    from: Int,
  ): FrameHeader? {
    var i = from
    while (i < head.size - 4) {
      if (head[i] == 0xFF.toByte() && (head[i + 1].toInt() and 0xE0) == 0xE0) {
        val parsed = decodeFrame(head, i)
        if (parsed != null) return parsed
      }
      i++
    }
    return null
  }

  private fun decodeFrame(
    head: ByteArray,
    i: Int,
  ): FrameHeader? {
    val b1 = head[i + 1].toInt() and 0xFF
    val b2 = head[i + 2].toInt() and 0xFF
    val b3 = head[i + 3].toInt() and 0xFF
    val versionBits = (b1 shr 3) and 0x3
    val layerBits = (b1 shr 1) and 0x3
    if (versionBits == 1 || layerBits != 1) return null // reserved version or not layer III
    val bitrateIndex = (b2 shr 4) and 0xF
    val samplerateIndex = (b2 shr 2) and 0x3
    if (bitrateIndex == 0 || bitrateIndex == 15 || samplerateIndex == 3) return null

    val sampleRate = when (versionBits) {
      3 -> MPEG1_SAMPLE_RATES[samplerateIndex]
      2 -> MPEG2_SAMPLE_RATES[samplerateIndex]
      else -> MPEG25_SAMPLE_RATES[samplerateIndex]
    }
    val bitrateKbps = when (versionBits) {
      3 -> MPEG1_L3_BITRATES_KBPS[bitrateIndex]
      else -> MPEG2_L3_BITRATES_KBPS[bitrateIndex]
    }
    val samplesPerFrame = if (versionBits == 3) 1_152 else 576
    val channelMode = (b3 shr 6) and 0x3
    return FrameHeader(
      offset = i,
      versionMpeg1 = versionBits == 3,
      channelMode = channelMode,
      sampleRate = sampleRate,
      samplesPerFrame = samplesPerFrame,
      bitrateKbps = bitrateKbps,
    )
  }

  /** Reads the frame count from a Xing/Info header inside the first frame. */
  private fun findXingFrameCount(
    head: ByteArray,
    frameOffset: Int,
    mpeg1: Boolean,
  ): Long? {
    // "Xing" sits after the side information; search a small window
    val searchEnd = minOf(head.size - 8, frameOffset + 200)
    var i = frameOffset + 4
    while (i < searchEnd) {
      val isXing = head[i] == 'X'.code.toByte() && head[i + 1] == 'i'.code.toByte() &&
        head[i + 2] == 'n'.code.toByte() && head[i + 3] == 'g'.code.toByte()
      val isInfo = head[i] == 'I'.code.toByte() && head[i + 1] == 'n'.code.toByte() &&
        head[i + 2] == 'f'.code.toByte() && head[i + 3] == 'o'.code.toByte()
      if (isXing || isInfo) {
        val flags = ((head[i + 4].toInt() and 0xFF) shl 24) or
          ((head[i + 5].toInt() and 0xFF) shl 16) or
          ((head[i + 6].toInt() and 0xFF) shl 8) or
          (head[i + 7].toInt() and 0xFF)
        if (flags and 0x1 != 0 && i + 12 < head.size) {
          var frames = 0L
          for (b in 0 until 4) {
            frames = (frames shl 8) or (head[i + 8 + b].toLong() and 0xFF)
          }
          if (frames > 0) return frames
        }
        return null
      }
      i++
    }
    // silence unused warning for mono offset distinction (search covers both)
    return null
  }

  /** Cbr fallback duration for [bitrateKbps] over [contentLength] bytes. */
  public fun cbrDurationMs(
    contentLength: Long,
    bitrateKbps: Int,
  ): Long = ceil(contentLength * 8.0 / bitrateKbps).toLong()

  private data class FrameHeader(
    val offset: Int,
    val versionMpeg1: Boolean,
    val channelMode: Int,
    val sampleRate: Int,
    val samplesPerFrame: Int,
    val bitrateKbps: Int,
  )
}
