package voice.core.scanner

import android.content.Context
import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import voice.core.data.BookId
import voice.core.data.repo.BookRepository
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class CoverSaverTest {

  private val tmp = Files.createTempDirectory("coverSaver").toFile()

  // get/updateBook are stubbed outside the mockk builder block: inside it,
  // the scope's own members shadow the mock's suspend methods
  private val repo = mockk<BookRepository>()

  init {
    coEvery { repo.get(any()) } answers { null }
    coEvery { repo.updateBook(any(), any()) } just Runs
  }

  private val coverSaver = CoverSaver(
    repo = repo,
    context = mockk<Context> {
      every { filesDir } returns tmp
    },
  )

  @Test
  fun aUserSavedCoverIsMarkedAsUserChosen() = runTest {
    coverSaver.save(BookId("content://books/one"), bitmap(), fromUser = true)

    assertTrue(coverSaver.hasUserChosenCover(BookId("content://books/one")))
  }

  @Test
  fun anAutomaticallySavedCoverIsNotMarkedAsUserChosen() = runTest {
    coverSaver.save(BookId("content://books/one"), bitmap())

    assertFalse(coverSaver.hasUserChosenCover(BookId("content://books/one")))
  }

  private fun bitmap(): Bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
}
