package voice.features.bookOverview.deleteBook

import android.app.Application
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.documentfile.provider.DocumentFile
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.launch
import voice.core.common.DispatcherProvider
import voice.core.common.MainScope
import voice.core.data.BookId
import voice.core.data.folders.AudiobookFolders
import voice.core.data.repo.BookRepository
import voice.core.data.toUri
import voice.core.logging.api.Logger
import voice.core.scanner.MediaScanTrigger
import voice.features.bookOverview.bottomSheet.BottomSheetItem
import voice.features.bookOverview.bottomSheet.BottomSheetItemViewModel
import voice.features.bookOverview.di.BookOverviewScope

@SingleIn(BookOverviewScope::class)
@ContributesIntoSet(BookOverviewScope::class)
class DeleteBookViewModel(
  private val application: Application,
  private val audiobookFolders: AudiobookFolders,
  private val mediaScanTrigger: MediaScanTrigger,
  private val bookRepository: BookRepository,
  dispatcherProvider: DispatcherProvider,
) : BottomSheetItemViewModel {

  private val scope = MainScope(dispatcherProvider)

  private val _state = mutableStateOf<DeleteBookViewState?>(null)
  internal val state: State<DeleteBookViewState?> get() = _state

  override suspend fun items(bookId: BookId): List<BottomSheetItem> {
    return listOf(BottomSheetItem.DeleteBook)
  }

  override suspend fun onItemClick(
    bookId: BookId,
    item: BottomSheetItem,
  ) {
    if (item != BottomSheetItem.DeleteBook) return

    _state.value = DeleteBookViewState(
      id = bookId,
      deleteCheckBoxChecked = false,
      canDeleteFiles = bookId.toUri().scheme?.lowercase() !in setOf("http", "https"),
      fileToDelete = bookId.toUri().pathSegments
        .let { segments ->
          val result = segments.lastOrNull()?.removePrefix("primary:")
          if (result.isNullOrEmpty()) {
            Logger.w("Could not determine path for $segments")
            segments.joinToString(separator = "\"")
          } else {
            result
          }
        },
    )
  }

  internal fun onDismiss() {
    _state.value = null
  }

  internal fun onDeleteCheckBoxCheck(checked: Boolean) {
    _state.value = _state.value?.copy(deleteCheckBoxChecked = checked)
  }

  internal fun onConfirmDeletion() {
    val state = _state.value
    if (state != null) {
      scope.launch {
        // remote (webdav) books live on the server: there are no local files
        // to delete, and attempting it would only fail silently. Dropping the
        // shelf registration is enough; the book can be re-added any time from
        // the server browser, which also lifts the exclusion.
        if (state.deleteCheckBoxChecked && state.canDeleteFiles) {
          val documentFile = DocumentFile.fromSingleUri(application, state.id.toUri())
          if (documentFile?.delete() != true) {
            Logger.w("Could not delete the files of ${state.id}")
          }
        }
        // The book always leaves the shelf. Keeping the files is the default,
        // so a book can be dropped from the library without touching the audio
        // on the device. The registration has to go in both cases, otherwise
        // the next scan would put the book back on the shelf.
        audiobookFolders.removeBookRegistration(state.id)
        bookRepository.updateBook(state.id) { it.copy(isActive = false) }
        mediaScanTrigger.scan(restartIfScanning = true)
      }
    }
    _state.value = null
  }
}

data class DeleteBookViewState(
  val id: BookId,
  val deleteCheckBoxChecked: Boolean,
  val canDeleteFiles: Boolean,
  val fileToDelete: String,
)
