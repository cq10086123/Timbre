package voice.core.common.comparator

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
class NaturalOrderComparatorTest {

  private val testFolder = TemporaryFolder()

  @Rule
  fun testFolder() = testFolder

  @Before
  fun setUp() {
    testFolder.create()
  }

  private fun assertSorted(values: List<String>) {
    assertEquals(
      expected = values,
      actual = values.toMutableList()
        .apply {
          repeat(values.size / 2) {
            add(removeAt(0))
          }
        }
        .sortedWith(NaturalOrderComparator.stringComparator),
    )
  }

  private fun testFiles(): List<File> {
    testFolder.newFolder("folder", "subfolder", "subsubfolder")
    testFolder.newFolder("storage", "emulated", "0")
    testFolder.newFolder("xFolder")

    return listOf(
      testFolder.newFile("folder/subfolder/subsubfolder/test2.mp3"),
      testFolder.newFile("folder/subfolder/test.mp3"),
      testFolder.newFile("folder/subfolder/test2.mp3"),
      testFolder.newFile("folder/a.jpg"),
      testFolder.newFile("folder/aC.jpg"),
      testFolder.newFile("storage/emulated/0/1.ogg"),
      testFolder.newFile("storage/emulated/0/2.ogg"),
      testFolder.newFile("xFolder/d.jpg"),
      testFolder.newFile("1.mp3"),
      testFolder.newFile("a.jpg"),
    )
  }

  @Test
  fun naturalNumberOrder() {
    assertSorted(
      listOf(
        "00 I",
        "00 Introduction",
        "1",
        "01 How to build a universe",
        "01 I",
        "2",
        "9",
        "10",
      ),
    )
  }

  @Test
  fun caseInsensitiveLetterOrder() {
    assertSorted(
      listOf(
        "a",
        "Ab",
        "aC",
        "Ba",
        "cA",
        "D",
        "e",
      ),
    )
  }

  @Test
  fun naturalFolderOrder() {
    assertSorted(
      listOf(
        "folder1/1.mp3",
        "folder1/10.mp3",
        "folder2/2.mp3",
        "folder10/1.mp3",
      ),
    )
  }

  @Test
  fun accentedLettersUseRootCollation() {
    assertSorted(
      listOf(
        "ä",
        "ä",
        "b",
        "ö",
      ),
    )
  }

  @Test
  fun accentedTitleOrder() {
    assertSorted(
      listOf(
        "Čtyři dohody",
        "Čtyři tisíce týdnů",
        "Maryša",
        "R.U.R.",
        "Zákony lidské přirozenosti",
        "Život s vysokou inteligencí",
      ),
    )
  }

  @Test
  fun uriComparatorContent() {
    val sortedNames = listOf(
      "00 I",
      "00 Introduction",
      "1",
      "01 How to build a universe",
      "01 I",
      "2",
      "9",
      "10",
      "a",
      "Ab",
      "aC",
      "Ba",
      "cA",
      "D",
      "e",
      "folder1/1.mp3",
      "folder1/10.mp3",
      "folder2/2.mp3",
      "folder10/1.mp3",
    )

    fun uriFor(name: String) = Uri.Builder()
      .scheme("content")
      .authority("com.android.externalstorage.documents")
      .appendPath("tree")
      .appendPath("primary:audiobooks")
      .appendPath("document")
      .appendPath("primary:audiobooks/$name")
      .build()

    val expected = sortedNames.map(::uriFor)
    val shuffled = sortedNames.shuffled().map(::uriFor)

    assertEquals(
      expected = expected,
      actual = shuffled.sortedWith(NaturalOrderComparator.uriComparator),
    )
  }

  @Test
  fun uriComparatorFiles() {
    val files = testFiles()
    val byPath = mapOf(
      "1.mp3" to files.single { it.name == "1.mp3" && it.parentFile == testFolder.root },
      "a.jpg" to files.single { it.name == "a.jpg" && it.parentFile == testFolder.root },
      "folder/a.jpg" to files.single { it.path.endsWith("folder/a.jpg") },
      "folder/aC.jpg" to files.single { it.path.endsWith("folder/aC.jpg") },
      "folder/subfolder/subsubfolder/test2.mp3" to files.single { it.path.endsWith("subsubfolder/test2.mp3") },
      "folder/subfolder/test.mp3" to files.single { it.path.endsWith("subfolder/test.mp3") && !it.path.contains("subsubfolder") },
      "folder/subfolder/test2.mp3" to files.single { it.path.endsWith("subfolder/test2.mp3") && !it.path.contains("subsubfolder") },
      "storage/emulated/0/1.ogg" to files.single { it.name == "1.ogg" },
      "storage/emulated/0/2.ogg" to files.single { it.name == "2.ogg" },
      "xFolder/d.jpg" to files.single { it.name == "d.jpg" },
    )
    val expectedOrder = listOf(
      "1.mp3",
      "a.jpg",
      "folder/a.jpg",
      "folder/aC.jpg",
      "folder/subfolder/subsubfolder/test2.mp3",
      "folder/subfolder/test.mp3",
      "folder/subfolder/test2.mp3",
      "storage/emulated/0/1.ogg",
      "storage/emulated/0/2.ogg",
      "xFolder/d.jpg",
    ).map(byPath::getValue)

    val uris = expectedOrder.map { Uri.fromFile(it) }

    assertEquals(
      expected = uris,
      actual = uris.shuffled().sortedWith(NaturalOrderComparator.uriComparator),
    )
  }

  @Test
  fun fullWidthDigitsSortLikeAsciiDigits() {
    assertSorted(
      listOf(
        "第１集",
        "第2集",
        "第１０集",
      ),
    )
  }

  @Test
  fun chineseNumeralsAfterCounterPrefixSortNaturally() {
    assertSorted(
      listOf(
        "第一章 缘起",
        "第二章 风起",
        "第十章 惊变",
        "第十一章 旧友",
        "第二十章 重逢",
        "第九十九章 归途",
        "第一百章 归一",
        "第一百零一章 新篇",
        "第二百章 终章",
      ),
    )
  }

  @Test
  fun chineseNumeralsAfterOtherCountersSortNaturally() {
    assertSorted(
      listOf(
        "卷一",
        "卷二",
        "卷十",
        "卷十一",
        "卷二十",
        "卷一百",
      ),
    )
  }

  @Test
  fun commonEpisodeNamingStylesAllSortNaturally() {
    // 第1集 / 1集 / 001 集 / 第一集 / 全角第１集 must all order by number
    assertSorted(
      listOf(
        "第1集 序章.mp3",
        "第2集 出发.mp3",
        "第9集 风波.mp3",
        "第10集 汇合.mp3",
        "第11集 新篇.mp3",
        "第20集 重逢.mp3",
      ),
    )
    assertSorted(
      listOf(
        "1集.mp3",
        "2集.mp3",
        "9集.mp3",
        "10集.mp3",
        "11集.mp3",
        "20集.mp3",
      ),
    )
    assertSorted(
      listOf(
        "001 集 序章.mp3",
        "002 集 出发.mp3",
        "009 集 风波.mp3",
        "010 集 汇合.mp3",
        "011 集 新篇.mp3",
        "020 集 重逢.mp3",
      ),
    )
    assertSorted(
      listOf(
        "第一集 序章.mp3",
        "第二集 出发.mp3",
        "第九集 风波.mp3",
        "第十集 汇合.mp3",
        "第十一集 新篇.mp3",
        "第二十集 重逢.mp3",
      ),
    )
    assertSorted(
      listOf(
        "第１集 序章.mp3",
        "第２集 出发.mp3",
        "第９集 风波.mp3",
        "第１０集 汇合.mp3",
        "第１１集 新篇.mp3",
        "第２０集 重逢.mp3",
      ),
    )
    // mixed Arabic and Chinese numerals after the same counter 第 collapse
    // to the same normalized form
    assertEquals(
      expected = NaturalOrderComparator.stringComparator.compare("第1集", "第一集"),
      actual = 0,
    )
  }

  @Test
  fun chineseNumeralsWithoutCounterPrefixAreLeftUntouched() {
    // "一千" here is prose, not an episode number
    val prose = "一千个为什么"
    assertEquals(expected = prose, actual = ChineseNumeralNormalizer.normalize(prose))

    assertEquals(
      expected = "第20章",
      actual = ChineseNumeralNormalizer.normalize("第二十章"),
    )
    assertEquals(
      expected = "第101回",
      actual = ChineseNumeralNormalizer.normalize("第一百零一回"),
    )
    assertEquals(
      expected = "第101集",
      actual = ChineseNumeralNormalizer.normalize("第一〇一集"),
    )
    assertEquals(
      expected = "第2026集",
      actual = ChineseNumeralNormalizer.normalize("第二零二六集"),
    )
  }

  @After
  fun tearDown() {
    testFolder.delete()
  }
}
