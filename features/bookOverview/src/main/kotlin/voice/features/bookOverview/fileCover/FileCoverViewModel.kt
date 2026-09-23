package voice.features.bookOverview.fileCover

import android.net.Uri
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.SingleIn
import voice.core.data.BookId
import voice.core.online.OnlineUri
import voice.features.bookOverview.bottomSheet.BottomSheetItem
import voice.features.bookOverview.bottomSheet.BottomSheetItemViewModel
import voice.features.bookOverview.di.BookOverviewScope
import voice.navigation.Destination
import voice.navigation.Navigator

@SingleIn(BookOverviewScope::class)
@ContributesIntoSet(BookOverviewScope::class)
class FileCoverViewModel(private val navigator: Navigator) : BottomSheetItemViewModel {

  private var bookId: BookId? = null

  override suspend fun items(bookId: BookId): List<BottomSheetItem> {
    // online books show the cover url of the source: a local cover file
    // would never be picked up
    if (OnlineUri.parseBookUri(bookId.value) != null) return emptyList()
    return listOf(BottomSheetItem.FileCover)
  }

  override suspend fun onItemClick(
    bookId: BookId,
    item: BottomSheetItem,
  ) {
    if (item == BottomSheetItem.FileCover) {
      this.bookId = bookId
    }
  }

  fun onImagePicked(uri: Uri) {
    val bookId = bookId ?: return
    navigator.goTo(Destination.EditCover(bookId, uri))
  }
}
