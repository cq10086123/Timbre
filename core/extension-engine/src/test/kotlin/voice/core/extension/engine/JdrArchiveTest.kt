package voice.core.extension.engine

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JdrArchiveTest {

  private fun validManifest(sources: String = """[{"id":"demo","name":"演示源","script":"demo.js"}]""") =
    """{"id":"com.example.demo","name":"演示包","version":"1.0.0","author":"tester",
       "sources":$sources}"""

  private fun zip(vararg entries: Pair<String, String>): ByteArray {
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { zip ->
      entries.forEach { (name, content) ->
        zip.putNextEntry(ZipEntry(name))
        zip.write(content.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
      }
    }
    return out.toByteArray()
  }

  private val demoScript = """
    ;(() => {
      registerSource({ id: 'demo', async search() { return [] } })
    })()
  """.trimIndent()

  @Test
  fun parsesValidPackage() {
    val archive = JdrArchive.parse(
      zip(
        "manifest.json" to validManifest(),
        "demo.js" to demoScript,
      ),
    )
    assertEquals("com.example.demo", archive.manifest.id)
    assertEquals(1, archive.manifest.sources.size)
    assertEquals("演示源", archive.manifest.sources[0].name)
    assertEquals(demoScript, archive.scriptFor(archive.manifest.sources[0]))
  }

  @Test
  fun parsesMultiSourcePackage() {
    val sources =
      """[{"id":"a","name":"甲","script":"a.js"},{"id":"b","name":"乙","script":"b.js"}]"""
    val archive = JdrArchive.parse(
      zip(
        "manifest.json" to validManifest(sources = sources),
        "a.js" to demoScript.replace("'demo'", "'a'"),
        "b.js" to demoScript.replace("'demo'", "'b'"),
      ),
    )
    assertEquals(2, archive.manifest.sources.size)
    assertEquals("a.js", archive.manifest.sources[0].script)
    assertTrue(archive.scriptFor(archive.manifest.sources[0]).contains("'a'"))
    assertTrue(archive.scriptFor(archive.manifest.sources[1]).contains("'b'"))
  }

  @Test
  fun rejectsMissingManifest() {
    val e = assertFailsWith<JdrFormatException> {
      JdrArchive.parse(zip("demo.js" to demoScript))
    }
    assertTrue(e.message!!.contains("manifest"))
  }

  @Test
  fun rejectsMissingScript() {
    val e = assertFailsWith<JdrFormatException> {
      JdrArchive.parse(zip("manifest.json" to validManifest()))
    }
    assertTrue(e.message!!.contains("demo.js"), e.message)
  }

  @Test
  fun rejectsPathTraversal() {
    val e = assertFailsWith<JdrFormatException> {
      JdrArchive.parse(
        zip(
          "manifest.json" to validManifest(),
          "../evil.js" to demoScript,
        ),
      )
    }
    assertTrue(e.message!!.contains("非法"), e.message)
  }

  @Test
  fun rejectsBadSourceId() {
    val e = assertFailsWith<JdrFormatException> {
      JdrArchive.parse(
        zip(
          "manifest.json" to validManifest(sources = """[{"id":"演示!","name":"x","script":"demo.js"}]"""),
          "demo.js" to demoScript,
        ),
      )
    }
    assertTrue(e.message!!.contains("id"), e.message)
  }

  @Test
  fun rejectsDuplicateSourceIds() {
    val sources =
      """[{"id":"a","name":"A","script":"a.js"},{"id":"a","name":"A2","script":"b.js"}]"""
    val e = assertFailsWith<JdrFormatException> {
      JdrArchive.parse(
        zip(
          "manifest.json" to validManifest(sources = sources),
          "a.js" to demoScript,
          "b.js" to demoScript,
        ),
      )
    }
    assertTrue(e.message!!.contains("重复"), e.message)
  }

  @Test
  fun rejectsUnknownCapability() {
    val sources = """[{"id":"a","name":"A","script":"a.js","capabilities":["magic"]}]"""
    val e = assertFailsWith<JdrFormatException> {
      JdrArchive.parse(
        zip(
          "manifest.json" to validManifest(sources = sources),
          "a.js" to demoScript,
        ),
      )
    }
    assertTrue(e.message!!.contains("magic"), e.message)
  }

  @Test
  fun rejectsPackageThatInflatesPastTheCap() {
    // 17mb of text compresses to a few kb: the zip size says nothing about
    // what the package expands to, which is why the total is capped as well
    val blobs = (1..17).map { index -> "blob$index.js" to "a".repeat(1024 * 1024) }
    val e = assertFailsWith<JdrFormatException> {
      JdrArchive.parse(zip("manifest.json" to validManifest(), *blobs.toTypedArray()))
    }
    assertTrue(e.message!!.contains("解压后"), e.message)
  }

  @Test
  fun rejectsEmptyFile() {
    assertFailsWith<JdrFormatException> { JdrArchive.parse(ByteArray(0)) }
  }
}
