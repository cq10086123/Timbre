package voice.features.bookOverview.onlineActions

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CacheFailureTitleTest {

  @Test
  fun `a title that already numbers the chapter is left alone`() {
    assertFalse(cacheFailureNeedsChapterPrefix("第38集 二愣子", 38))
    assertFalse(cacheFailureNeedsChapterPrefix("第 38 集 二愣子", 38))
    assertFalse(cacheFailureNeedsChapterPrefix("第 38 话", 38))
    assertFalse(cacheFailureNeedsChapterPrefix("凡人修仙传 第38章", 38))
  }

  @Test
  fun `a bare number in front counts as numbered`() {
    assertFalse(cacheFailureNeedsChapterPrefix("38 二愣子", 38))
    assertFalse(cacheFailureNeedsChapterPrefix("038、二愣子", 38))
    assertFalse(cacheFailureNeedsChapterPrefix("38", 38))
  }

  @Test
  fun `a longer number that merely contains the chapter is not numbered`() {
    // "第11集" must not pass for chapter 1, "第13集" must not pass for chapter 3
    assertTrue(cacheFailureNeedsChapterPrefix("第11集", 1))
    assertTrue(cacheFailureNeedsChapterPrefix("第13集", 3))
    // "1000集的故事" only looks like a number: it is not this chapter's
    assertTrue(cacheFailureNeedsChapterPrefix("1000集的故事", 1))
  }

  @Test
  fun `a title without any number gets the prefix`() {
    assertTrue(cacheFailureNeedsChapterPrefix("序章", 1))
    assertTrue(cacheFailureNeedsChapterPrefix("", 7))
  }

  @Test
  fun `an unknown chapter order never gets a prefix`() {
    // the source said nothing about ordering: inventing "第 0 集" would mislead
    assertFalse(cacheFailureNeedsChapterPrefix("序章", 0))
  }
}
