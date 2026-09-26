package voice.core.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import voice.core.source.jdr.JdrFormatException
import voice.core.source.jdr.JdrLoader
import java.io.ByteArrayOutputStream

class JdrLoaderTest {

  private fun zipOf(vararg entries: Pair<String, String>): ByteArray {
    // ZipOutputStream rejects duplicate entries outright - exactly the case
    // one test needs to construct - so entries are written by hand (store).
    fun crc32(data: ByteArray): Long {
      val crc = java.util.zip.CRC32()
      crc.update(data)
      return crc.value
    }

    fun localHeader(name: ByteArray, data: ByteArray): ByteArray {
      val out = ByteArrayOutputStream()
      val le = { v: Long, n: Int ->
        for (i in 0 until n) out.write(((v shr (8 * i)) and 0xff).toInt())
      }
      le(0x04034b50, 4); le(20, 2); le(0x0800, 2); le(0, 2); le(0, 2); le(0x21, 2)
      le(crc32(data), 4); le(data.size.toLong(), 4); le(data.size.toLong(), 4)
      le(name.size.toLong(), 2); le(0, 2)
      out.write(name)
      out.write(data)
      return out.toByteArray()
    }

    fun centralHeader(name: ByteArray, data: ByteArray, offset: Int): ByteArray {
      val out = ByteArrayOutputStream()
      val le = { v: Long, n: Int ->
        for (i in 0 until n) out.write(((v shr (8 * i)) and 0xff).toInt())
      }
      le(0x02014b50, 4); le(20, 2); le(20, 2); le(0x0800, 2); le(0, 2); le(0, 2); le(0x21, 2)
      le(crc32(data), 4); le(data.size.toLong(), 4); le(data.size.toLong(), 4)
      le(name.size.toLong(), 2); le(0, 2); le(0, 2); le(0, 2); le(0, 4)
      le(offset.toLong(), 4)
      out.write(name)
      return out.toByteArray()
    }

    val locals = ArrayList<ByteArray>()
    val centrals = ArrayList<ByteArray>()
    var offset = 0
    for ((name, content) in entries) {
      val nameBytes = name.toByteArray(Charsets.UTF_8)
      val data = content.toByteArray(Charsets.UTF_8)
      val local = localHeader(nameBytes, data)
      locals.add(local)
      centrals.add(centralHeader(nameBytes, data, offset))
      offset += local.size
    }
    val centralBytes = centrals.reduceOrNull(ByteArray::plus) ?: ByteArray(0)
    val end = ByteArrayOutputStream()
    val le = { v: Long, n: Int ->
      for (i in 0 until n) end.write(((v shr (8 * i)) and 0xff).toInt())
    }
    le(0x06054b50, 4); le(0, 2); le(0, 2); le(entries.size.toLong(), 2); le(entries.size.toLong(), 2)
    le(centralBytes.size.toLong(), 4); le(offset.toLong(), 4); le(0, 2)
    return (locals.reduceOrNull(ByteArray::plus) ?: ByteArray(0)) + centralBytes + end.toByteArray()
  }

  private val manifest =
    """{"formatVersion":1,"packageId":"demo","name":"Demo","sources":[{"id":"a","name":"A"}]}"""

  @Test
  fun `loads a valid package`() {
    val loader = JdrLoader()
    val pkg = loader.load(zipOf("manifest.json" to manifest, "bundle.js" to "registerSource({id:'a',name:'A'},{});"))
    assertEquals("demo", pkg.manifest.packageId)
    assertEquals(1, pkg.manifest.sources.size)
    assertTrue(pkg.bundle.contains("registerSource"))
  }

  @Test
  fun `rejects missing manifest`() {
    try {
      val _ = JdrLoader().load(zipOf("bundle.js" to "x"))
      fail("expected JdrFormatException")
    } catch (e: JdrFormatException) {
      assertTrue(e.message!!.contains("manifest.json"))
    }
  }

  @Test
  fun `rejects duplicate entries`() {
    try {
      val _ = JdrLoader().load(zipOf("manifest.json" to manifest, "bundle.js" to "a", "bundle.js" to "b"))
      fail("expected JdrFormatException")
    } catch (e: JdrFormatException) {
      assertTrue(e.message!!.contains("duplicate"))
    }
  }

  @Test
  fun `rejects path traversal`() {
    try {
      val _ = JdrLoader().load(zipOf("manifest.json" to manifest, "../evil.js" to "x", "bundle.js" to "a"))
      fail("expected JdrFormatException")
    } catch (e: JdrFormatException) {
      assertTrue(e.message!!.contains("escapes"))
    }
  }

  @Test
  fun `rejects wrong format version`() {
    val bad = """{"formatVersion":99,"packageId":"demo","name":"D","sources":[{"id":"a","name":"A"}]}"""
    try {
      val _ = JdrLoader().load(zipOf("manifest.json" to bad, "bundle.js" to "x"))
      fail("expected JdrFormatException")
    } catch (e: JdrFormatException) {
      assertTrue(e.message!!.contains("format version"))
    }
  }

  @Test
  fun `rejects unsupported package ids`() {
    val bad = """{"formatVersion":1,"packageId":"Evil Pkg","name":"D","sources":[{"id":"a","name":"A"}]}"""
    try {
      val _ = JdrLoader().load(zipOf("manifest.json" to bad, "bundle.js" to "x"))
      fail("expected JdrFormatException")
    } catch (e: JdrFormatException) {
      assertTrue(e.message!!.contains("packageId"))
    }
  }

  @Test
  fun `rejects missing bundle entry`() {
    try {
      val _ = JdrLoader().load(zipOf("manifest.json" to manifest))
      fail("expected JdrFormatException")
    } catch (e: JdrFormatException) {
      assertTrue(e.message!!.contains("bundle entry missing"))
    }
  }
}
