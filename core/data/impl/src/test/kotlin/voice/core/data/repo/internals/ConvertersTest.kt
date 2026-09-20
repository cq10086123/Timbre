package voice.core.data.repo.internals

import voice.core.data.MarkData
import java.io.File
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

class ConvertersTest {

  @Test
  fun instant() {
    test(Instant.now(), Converters::fromInstant, Converters::toInstant)
  }

  @Test
  fun uuid() {
    test(Uuid.random(), Converters::fromUuid, Converters::toUuid)
  }

  @Test
  fun file() {
    // assert against absolutePath instead of the original file: java.io.File
    // normalizes differently per platform (windows prepends a drive letter
    // and uses backslashes), and the converter only guarantees that
    // serialize/deserialize are stable relative to absolutePath.
    val original = File("/sdcard/audiobooks/potter.mp3")
    val converters = Converters()
    val serialized = converters.fromFile(original)
    assertEquals(expected = original.absolutePath, actual = serialized)
    assertEquals(expected = original.absolutePath, actual = converters.toFile(serialized).absolutePath)
  }

  @Test
  fun marksEmpty() {
    test(emptyList(), Converters::fromMarks, Converters::toMarks)
  }

  @Test
  fun marks() {
    test(
      value = listOf(
        MarkData(0L, "Hello"),
        MarkData(5L, "World"),
        MarkData(Long.MIN_VALUE, ""),
      ),
      serialize = Converters::fromMarks,
      deSerialize = Converters::toMarks,
    )
  }

  private fun <S, D> test(
    value: D,
    serialize: (Converters.(D) -> S),
    deSerialize: (Converters.(S) -> D),
  ) {
    val converters = Converters()
    val serialized: S = converters.serialize(value)
    val deSerialized: D = converters.deSerialize(serialized)
    assertEquals(expected = value, actual = deSerialized)
  }
}
