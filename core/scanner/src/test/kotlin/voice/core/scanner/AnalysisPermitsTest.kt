package voice.core.scanner

import kotlin.test.Test
import kotlin.test.assertEquals

class AnalysisPermitsTest {

  @Test
  fun theAnalysisBudgetIsSplitAcrossTheChosenParallelism() {
    assertEquals(expected = 6, actual = analysisPermitsFor(1))
    assertEquals(expected = 3, actual = analysisPermitsFor(2))
    assertEquals(expected = 2, actual = analysisPermitsFor(3))
  }

  @Test
  fun everySupportedParallelismUsesTheWholeBudgetWithoutOverflowingIt() {
    (1..MAX_IMPORT_PARALLELISM).forEach { parallelism ->
      val permits = analysisPermitsFor(parallelism)
      assertEquals(
        expected = parallelism,
        actual = ANALYSIS_PERMITS / permits,
        message = "parallelism $parallelism must fit $permits permits each",
      )
      assertEquals(
        expected = 0,
        actual = ANALYSIS_PERMITS % parallelism,
        message = "the budget must divide evenly by parallelism $parallelism",
      )
    }
  }

  @Test
  fun unsupportedValuesFallBackToTheClosestSupportedLevel() {
    assertEquals(expected = analysisPermitsFor(1), actual = analysisPermitsFor(0))
    assertEquals(expected = analysisPermitsFor(1), actual = analysisPermitsFor(-3))
    assertEquals(
      expected = analysisPermitsFor(MAX_IMPORT_PARALLELISM),
      actual = analysisPermitsFor(MAX_IMPORT_PARALLELISM + 5),
    )
  }
}
