package voice.core.update

import kotlin.test.Test
import kotlin.test.assertEquals

class ReleaseNotesFormatterTest {

  @Test
  fun `headings bullets links images and bold are reduced to display lines`() {
    val markdown = """
      ## What's Changed
      * fix: 在线书源设置不生效 @cq10086123
      - **新增** [下载页](https://example.com/download)
      ![logo](https://example.com/logo.png)
      Full Changelog: https://example.com/compare/v1..v2
    """.trimIndent()

    val lines = formatReleaseNotes(markdown)

    assertEquals(
      listOf(
        ReleaseNotesLine("What's Changed", isHeading = true),
        ReleaseNotesLine("•  fix: 在线书源设置不生效 @cq10086123"),
        ReleaseNotesLine("•  新增 下载页"),
        ReleaseNotesLine("logo"),
        ReleaseNotesLine("Full Changelog: https://example.com/compare/v1..v2"),
      ),
      lines,
    )
  }

  @Test
  fun `blank lines and marker only lines are dropped`() {
    val lines = formatReleaseNotes("## \n\n- \n正文")

    assertEquals(listOf(ReleaseNotesLine("正文")), lines)
  }

  @Test
  fun `heading level does not leak into the text`() {
    val lines = formatReleaseNotes("### 小标题")

    assertEquals(listOf(ReleaseNotesLine("小标题", isHeading = true)), lines)
  }
}
