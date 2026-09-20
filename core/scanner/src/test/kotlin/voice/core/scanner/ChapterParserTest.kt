package voice.core.scanner

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import voice.core.common.PlaybackIoGate
import voice.core.data.repo.ChapterRepoImpl
import voice.core.documentfile.CachedDocumentFile
import voice.core.documentfile.FileBasedDocumentFile
import voice.core.documentfile.nameWithoutExtension
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class ChapterParserTest {

  private val testFolder = TemporaryFolder()

  @Rule
  fun testFolder() = testFolder

  @Before
  fun setUp() {
    testFolder.create()
  }

  @Test
  fun parserSorts() = runTest {
    val audiobook = testFolder.newFolder("audiobook")
    testFolder.newFile("audiobook/Chapter 1.mp3")
    testFolder.newFile("audiobook/Chapter 2.mp3")
    testFolder.newFile("audiobook/Chapter 20.mp3")
    testFolder.newFile("audiobook/Chapter 3.mp3")
    testFolder.newFile("audiobook/Chapter 30.mp3")

    val chapterParser = ChapterParser(
      chapterRepo = ChapterRepoImpl(
        mockk {
          coEvery {
            chapter(any())
          } returns null
          coEvery {
            chapters(any())
          } returns emptyList()
          coEvery {
            insert(any())
          } just Runs
          coEvery {
            insertAll(any())
          } just Runs
        },
      ),
      mediaAnalyzer = mockk {
        coEvery {
          analyze(any())
        } coAnswers {
          val file = firstArg<CachedDocumentFile>()
          Metadata(
            duration = 1000,
            fileName = file.nameWithoutExtension(),
            artist = null,
            album = null,
            chapters = emptyList(),
            title = null,
            genre = null,
            narrator = null,
            series = null,
            part = null,
          )
        }
      },
      playbackIoGate = PlaybackIoGate(),
      scanProgressReporter = ScanProgressReporter(),
    )
    assertEquals(
      expected = listOf(
        "Chapter 1",
        "Chapter 2",
        "Chapter 3",
        "Chapter 20",
        "Chapter 30",
      ),
      actual = chapterParser.parse(FileBasedDocumentFile(audiobook))
        .chapters
        .map { it.name },
    )
  }

  @Test
  fun reportsChaptersInOrderWhileParsing() = runTest {
    val audiobook = testFolder.newFolder("audiobook")
    repeat(45) { index ->
      testFolder.newFile("audiobook/Chapter ${index + 1}.mp3")
    }

    val reports = mutableListOf<ChapterParseResult>()
    val result = chapterParser().parse(FileBasedDocumentFile(audiobook)) { reports += it }

    assertEquals(expected = 45, actual = result.chapters.size)
    assertTrue(reports.size >= 2)
    assertEquals(expected = result.chapters, actual = reports.last().chapters)
    reports.forEach { report ->
      // every report is a sorted prefix of the final result, so a book that is
      // still importing can already be played starting at its first chapter
      assertEquals(expected = report.chapters.sorted(), actual = report.chapters)
      assertEquals(
        expected = report.chapters,
        actual = result.chapters.take(report.chapters.size),
      )
    }
  }

  @Test
  fun firstChapterOfABatchIsReportedBeforeTheBatchFinished() = runTest {
    val audiobook = testFolder.newFolder("audiobook")
    testFolder.newFile("audiobook/Chapter 1.mp3")
    testFolder.newFile("audiobook/Chapter 2.mp3")

    // chapter 2 blocks in the analyzer; chapter 1 must still be published
    val releaseSecondChapter = CompletableDeferred<Unit>()
    val firstChapterReported = CompletableDeferred<ChapterParseResult>()
    val parser = ChapterParser(
      chapterRepo = ChapterRepoImpl(
        mockk {
          coEvery { chapter(any()) } returns null
          coEvery { chapters(any()) } returns emptyList()
          coEvery { insert(any()) } just Runs
          coEvery { insertAll(any()) } just Runs
        },
      ),
      mediaAnalyzer = mockk {
        coEvery { analyze(any()) } coAnswers {
          val file = firstArg<CachedDocumentFile>()
          if (file.name() == "Chapter 2.mp3") {
            releaseSecondChapter.await()
          }
          Metadata(
            duration = 1000,
            fileName = file.nameWithoutExtension(),
            artist = null,
            album = null,
            chapters = emptyList(),
            title = null,
            genre = null,
            narrator = null,
            series = null,
            part = null,
          )
        }
      },
      playbackIoGate = PlaybackIoGate(),
      scanProgressReporter = ScanProgressReporter(),
    )

    val result = CompletableDeferred<ChapterParseResult>()
    val parseJob = launch(UnconfinedTestDispatcher(testScheduler)) {
      parser.parse(FileBasedDocumentFile(audiobook)) { report ->
        if (report.chapters.isNotEmpty()) {
          firstChapterReported.complete(report)
        }
      }.let { result.complete(it) }
    }

    // a listener at the import frontier gets the first chapter of the batch
    // while the remaining chapters of the batch are still being analyzed
    val report = firstChapterReported.await()
    assertTrue(releaseSecondChapter.isActive)
    assertEquals(expected = "Chapter 1", actual = report.chapters.single().name)

    releaseSecondChapter.complete(Unit)
    parseJob.join()
    assertEquals(expected = 2, actual = result.await().chapters.size)
  }

  private fun chapterParser(): ChapterParser {
    return ChapterParser(
      chapterRepo = ChapterRepoImpl(
        mockk {
          coEvery {
            chapter(any())
          } returns null
          coEvery {
            chapters(any())
          } returns emptyList()
          coEvery {
            insert(any())
          } just Runs
          coEvery {
            insertAll(any())
          } just Runs
        },
      ),
      mediaAnalyzer = mockk {
        coEvery {
          analyze(any())
        } coAnswers {
          val file = firstArg<CachedDocumentFile>()
          Metadata(
            duration = 1000,
            fileName = file.nameWithoutExtension(),
            artist = null,
            album = null,
            chapters = emptyList(),
            title = null,
            genre = null,
            narrator = null,
            series = null,
            part = null,
          )
        }
      },
      playbackIoGate = PlaybackIoGate(),
      scanProgressReporter = ScanProgressReporter(),
    )
  }
}
