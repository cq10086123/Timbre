package voice.core.online

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.jsonPrimitive

/**
 * Accepts both JSON strings and numbers as a string (for example the download
 * site returns numeric album ids for one source and alphanumeric ids for
 * another), so a single result model can cover every source.
 */
internal object FlexibleStringSerializer : KSerializer<String> {
  override val descriptor = PrimitiveSerialDescriptor("FlexibleString", PrimitiveKind.STRING)

  override fun deserialize(decoder: Decoder): String {
    return if (decoder is JsonDecoder) {
      decoder.decodeJsonElement().jsonPrimitive.content
    } else {
      decoder.decodeString()
    }
  }

  override fun serialize(
    encoder: Encoder,
    value: String,
  ) {
    encoder.encodeString(value)
  }
}

/**
 * Accepts JSON numbers, numeric strings, empty strings and nulls as an Int
 * (0 for anything unparsable): the script based sources return
 * `"duration": ""` for tracks without a known length.
 */
internal object FlexibleIntSerializer : KSerializer<Int> {
  override val descriptor = PrimitiveSerialDescriptor("FlexibleInt", PrimitiveKind.INT)

  override fun deserialize(decoder: Decoder): Int {
    if (decoder !is JsonDecoder) return decoder.decodeInt()
    val primitive = decoder.decodeJsonElement().jsonPrimitive
    return when {
      primitive.isString -> primitive.content.trim().toIntOrNull() ?: 0
      else -> primitive.content.toIntOrNull() ?: 0
    }
  }

  override fun serialize(
    encoder: Encoder,
    value: Int,
  ) {
    encoder.encodeInt(value)
  }
}

/** [String] serializer alias for store creation convenience. */
internal val StringSerializer = String.serializer()
