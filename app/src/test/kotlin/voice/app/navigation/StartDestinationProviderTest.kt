package voice.app.navigation

import android.content.Intent
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import voice.app.MainActivity
import voice.core.data.BookId
import voice.core.data.folders.AudiobookFolders
import voice.core.data.folders.DocumentFileWithUri
import voice.core.data.folders.FolderType
import voice.navigation.Destination
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class StartDestinationProviderTest {

  @Test
  fun `shows onboarding when incomplete and no folders`() = runTest {
    val provider = provider(
      onboardingCompleted = false,
      hasFolders = false,
    )
    val result = provider(Intent())
    assertEquals(listOf(Destination.OnboardingWelcome), result.destinations)
    assertFalse(result.shouldPlayCurrent)
  }

  @Test
  fun `opens book overview when onboarding is done`() = runTest {
    val provider = provider(onboardingCompleted = true)
    val result = provider(Intent())
    assertEquals(listOf(Destination.BookOverview), result.destinations)
  }

  @Test
  fun `opens playback when go-to-book extra is set`() = runTest {
    val bookId = BookId("content://book")
    val provider = provider(
      onboardingCompleted = true,
      currentBook = bookId,
    )
    val intent = Intent().putExtra(MainActivity.NI_GO_TO_BOOK, true)
    val result = provider(intent)
    assertEquals(
      listOf(Destination.BookOverview, Destination.Playback(bookId)),
      result.destinations,
    )
    assertFalse(result.shouldPlayCurrent)
  }

  @Test
  fun `playCurrent marks playback without playing during resolution`() = runTest {
    val bookId = BookId("content://book")
    val provider = provider(
      onboardingCompleted = true,
      currentBook = bookId,
    )
    val intent = Intent().setAction("playCurrent")
    val result = provider(intent)
    assertEquals(
      listOf(Destination.BookOverview, Destination.Playback(bookId)),
      result.destinations,
    )
    assertTrue(result.shouldPlayCurrent)
  }

  private fun provider(
    onboardingCompleted: Boolean = false,
    hasFolders: Boolean = false,
    currentBook: BookId? = null,
  ): StartDestinationProvider {
    return StartDestinationProvider(
      onboardingCompletedStore = MemoryDataStore(onboardingCompleted),
      audiobookFolders = FakeAudiobookFolders(hasFolders),
      currentBookStore = MemoryDataStore(currentBook),
    )
  }
}

private class MemoryDataStore<T>(initial: T) : DataStore<T> {
  private val value = MutableStateFlow(initial)
  override val data: Flow<T> get() = value
  override suspend fun updateData(transform: suspend (t: T) -> T): T {
    return value.updateAndGet { transform(it) }
  }
}

private class FakeAudiobookFolders(private val hasFolders: Boolean) : AudiobookFolders {
  override fun all() = MutableStateFlow<Map<FolderType, List<DocumentFileWithUri>>>(emptyMap())
  override suspend fun add(
    uri: Uri,
    type: FolderType,
  ) = Unit
  override suspend fun remove(
    uri: Uri,
    folderType: FolderType,
  ) = Unit
  override suspend fun removeBookRegistration(bookId: BookId) = Unit
  override suspend fun migrateLegacyFolders() = Unit
  override suspend fun hasAnyFolders(): Boolean = hasFolders
}
