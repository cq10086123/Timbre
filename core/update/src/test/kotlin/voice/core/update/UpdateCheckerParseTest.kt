package voice.core.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UpdateCheckerParseTest {

  private val checker = UpdateChecker()

  @Test
  fun `update json with release notes`() {
    val body = """
      {"versionName":"3.5.336","versionCode":67,"releaseUrl":"https://example.com/latest",
       "releaseNotes":"## What's Changed\n* fix: 更新弹窗不显示内容"}
    """.trimIndent()

    val release = checker.parseRelease("https://cdn.jsdelivr.net/gh/cq10086123/Timbre@main/update.json", body)

    assertEquals("3.5.336", release!!.versionName)
    assertEquals("## What's Changed\n* fix: 更新弹窗不显示内容", release.releaseNotes)
  }

  @Test
  fun `update json without release notes yields null notes`() {
    val body = """{"versionName":"3.5.336","versionCode":67}"""

    val release = checker.parseRelease("https://cdn.jsdelivr.net/gh/cq10086123/Timbre@main/update.json", body)

    assertEquals("3.5.336", release!!.versionName)
    assertNull(release.releaseNotes)
  }

  @Test
  fun `update json without version is ignored`() {
    val body = """{"versionCode":67}"""

    assertNull(checker.parseRelease("https://cdn.jsdelivr.net/gh/cq10086123/Timbre@main/update.json", body))
  }

  @Test
  fun `github api tag maps to version name and body to release notes`() {
    val body = """{"tag_name":"v1.2.3","body":"## What's Changed\n* feat: bar"}"""

    val release = checker.parseRelease("https://api.github.com/repos/cq10086123/Timbre/releases/latest", body)

    assertEquals("1.2.3", release!!.versionName)
    assertEquals("## What's Changed\n* feat: bar", release.releaseNotes)
  }
}
