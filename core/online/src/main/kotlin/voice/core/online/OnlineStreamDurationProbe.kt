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

  private const val CHANNEL_MONO = 3
  private const val ID3V1_BYTES = 128L
  private const val VBRI_FRAMES_OFFSET = 14

  /**
   * Estimates the duration in ms.
   *
   * @param contentLength total stream size in bytes (from Content-Length or
   * the total of a Content-Range response)
   * @param head the first bytes of the stream (id3 tag + audio frames)
   */
  public fun estimateDurationMs(
    contentLength: Long,
    head: ByteArray,
  ): Long? {
    if (contentLength <= 0L || head.size < 8) return null
    val id3Size = id3Length(head) ?: return null
    val frame = findFrameHeader(head, id3Size) ?: return null
    if (frame.bitrateKbps <= 0 || frame.sampleRate <= 0) return null

    // vbr streams carry an exact frame count, either in a Xing/Info tag
    // (lame and friends) or in a VBRI tag (fraunhofer encoders)
    xingFrameCount(head, frame)?.let { frames ->
      return frames * frame.samplesPerFrame * 1_000L / frame.sampleRate
    }
    vbriFrameCount(head, frame)?.let { frames ->
      return frames * frame.samplesPerFrame * 1_000L / frame.sampleRate
    }
    // cbr fallback over the audio bytes only: the id3v2 tag and the 128 byte
    // id3v1 trailer are not audio and would inflate the estimate
    val audioBytes = (contentLength - id3Size - ID3V1_BYTES).coerceAtLeast(0L)
    return audioBytes * 8L / frame.bitrateKbps
  }

  /** Length of the leading ID3v2 tag in bytes, 0 when absent. */
  private fun id3Length(head: ByteArray): Int? {
    if (head.size < 10 || head[0] != 'I'.code.toByte() || head[1] != 'D'.code.toByte() || head[2] != '3'.code.toByte()) {
      return 0
    }
    return skipId3(head)
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
  private fun xingFrameCount(
    head: ByteArray,
    frame: FrameHeader,
  ): Long? {
    // the tag sits at a fixed offset after the side information, which
    // depends on the mpeg version and the channel mode
    val exact = frame.offset + 4 + when {
      frame.versionMpeg1 && frame.channelMode == CHANNEL_MONO -> 17
      frame.versionMpeg1 -> 32
      frame.channelMode == CHANNEL_MONO -> 9
      else -> 17
    }
    readFrameCount(head, exact)?.let { return it }
    // ...fall back to a small window scan for encoders that misplace it
    return findXingFrameCount(head, frame.offset)
  }

  private fun readFrameCount(
    head: ByteArray,
    tagOffset: Int,
  ): Long? {
    if (tagOffset < 0 || tagOffset + 12 > head.size) return null
    val isXing = head[tagOffset] == 'X'.code.toByte() && head[tagOffset + 1] == 'i'.code.toByte() &&
      head[tagOffset + 2] == 'n'.code.toByte() && head[tagOffset + 3] == 'g'.code.toByte()
    val isInfo = head[tagOffset] == 'I'.code.toByte() && head[tagOffset + 1] == 'n'.code.toByte() &&
      head[tagOffset + 2] == 'f'.code.toByte() && head[tagOffset + 3] == 'o'.code.toByte()
    if (!isXing && !isInfo) return null
    val flags = readU32(head, tagOffset + 4) ?: return null
    if (flags and 0x1L == 0L) return null
    return readU32(head, tagOffset + 8)?.takeIf { it > 0 }
  }

  /** Reads the frame count from a VBRI header (fraunhofer vbr files). */
  private fun vbriFrameCount(
    head: ByteArray,
    frame: FrameHeader,
  ): Long? {
    val base = frame.offset + 4 + 32
    if (base < 0 || base + VBRI_FRAMES_OFFSET + 4 > head.size) return null
    val isVb = head[base] == 'V'.code.toByte() && head[base + 1] == 'B'.code.toByte() &&
      head[base + 2] == 'R'.code.toByte() && head[base + 3] == 'I'.code.toByte()
    if (!isVb) return null
    return readU32(head, base + VBRI_FRAMES_OFFSET)?.takeIf { it > 0 }
  }

  private fun readU32(
    head: ByteArray,
    offset: Int,
  ): Long? {
    if (offset < 0 || offset + 4 > head.size) return null
    var value = 0L
    for (b in 0 until 4) {
      value = (value shl 8) or (head[offset + b].toLong() and 0xFF)
    }
    return value
  }

  /** Reads the frame count from a Xing/Info header inside the first frame. */
  private fun findXingFrameCount(
    head: ByteArray,
    frameOffset: Int,
  ): Long? {
    // "Xing" sits after the side information; search a small window
    val searchEnd = minOf(head.size - 8, frameOffset + 200)
    var i = frameOffset + 4
    while (i < searchEnd) {
      readFrameCount(head, i)?.let { return it }
      // a tag without a frame count still ends the search: whatever follows
      // is audio data, not another header
      if (isXingTag(head, i)) return null
      i++
    }
    // silence unused warning for mono offset distinction (search covers both)
    return null
  }

  private fun isXingTag(
    head: ByteArray,
    offset: Int,
  ): Boolean {
    if (offset < 0 || offset + 4 > head.size) return false
    val isXing = head[offset] == 'X'.code.toByte() && head[offset + 1] == 'i'.code.toByte() &&
      head[offset + 2] == 'n'.code.toByte() && head[offset + 3] == 'g'.code.toByte()
    val isInfo = head[offset] == 'I'.code.toByte() && head[offset + 1] == 'n'.code.toByte() &&
      head[offset + 2] == 'f'.code.toByte() && head[offset + 3] == 'o'.code.toByte()
    return isXing || isInfo
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
