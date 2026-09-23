package voice.core.playback.session

import android.app.Application
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaItem.ClippingConfiguration
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import voice.core.data.Book
import voice.core.data.BookComparator
import voice.core.data.BookContent
import voice.core.data.BookId
import voice.core.data.Chapter
import voice.core.data.durationMs
import voice.core.data.repo.BookContentRepo
import voice.core.data.repo.BookRepository
import voice.core.data.repo.ChapterRepo
import voice.core.data.store.CurrentBookStore
import voice.core.data.toUri
import java.io.File
import voice.core.strings.R as StringsR

@Inject
class MediaItemProvider(
  private val bookRepository: BookRepository,
  private val application: Application,
  private val chapterRepo: ChapterRepo,
  private val contentRepo: BookContentRepo,
  private val imageFileProvider: ImageFileProvider,
  @CurrentBookStore
  private val currentBookStoreId: DataStore<BookId?>,
) {

  fun root(): MediaItem = MediaItem(
    title = application.getString(StringsR.string.media_session_library_root),
    browsable = true,
    isPlayable = false,
    mediaId = MediaId.Root,
    mediaType = MediaType.AudioBookRoot,
  )

  private fun recentMediaItem(): MediaItem = MediaItem(
    title = application.getString(StringsR.string.media_session_library_recent),
    browsable = true,
    isPlayable = false,
    mediaId = MediaId.Recent,
    mediaType = MediaType.AudioBook,
  )

  /**
   * Recent root when a current book is known. Suspends so callers on a media
   * session callback never block a thread with runBlocking.
   */
  suspend fun recent(): MediaItem? {
    return recentMediaItem().takeIf { currentBookStoreId.data.first() != null }
  }

  suspend fun item(id: String): MediaItem? {
    val mediaId = id.toMediaIdOrNull() ?: return null
    return when (mediaId) {
      MediaId.Root -> root()
      is MediaId.Book -> {
        bookRepository.get(mediaId.id)?.let(::mediaItem)
      }
      is MediaId.Chapter -> {
        val content = contentRepo.get(mediaId.bookId) ?: return null
        chapterRepo.get(mediaId.chapterId)?.let {
          mediaItem(it, content)
        }
      }
      is MediaId.ChapterMark -> {
        val content = contentRepo.get(mediaId.bookId) ?: return null
        val chapter = chapterRepo.get(mediaId.chapterId) ?: return null
        val mark = chapter.chapterMarks.getOrNull(mediaId.markIndex) ?: return null
        mediaItem(
          playbackItem = PlaybackItem(
            index = 0,
            bookId = mediaId.bookId,
            chapter = chapter,
            markIndex = mediaId.markIndex,
            mark = mark,
          ),
          content = content,
        )
      }
      MediaId.Recent -> recent()
    }
  }

  fun mediaItemsWithStartPosition(book: Book): MediaItemsWithStartPosition {
    return mediaItemsWithStartPosition(book.content)
  }

  /**
   * Builds the start point of a whole book. It only needs the content: the
   * player resolves the chapters when it sets the items. Assembling the whole
   * book here would delay the start of a big book.
   */
  fun mediaItemsWithStartPosition(content: BookContent): MediaItemsWithStartPosition {
    return MediaItemsWithStartPosition(
      listOf(mediaItem(content)),
      C.INDEX_UNSET,
      C.TIME_UNSET,
    )
  }

  suspend fun mediaItemsWithStartPosition(id: String): MediaItemsWithStartPosition? {
    return when (val mediaId = id.toMediaIdOrNull()) {
      is MediaId.Book -> {
        val content = contentRepo.get(mediaId.id) ?: return null
        mediaItemsWithStartPosition(content)
      }
      is MediaId.Chapter, is MediaId.ChapterMark, MediaId.Root, MediaId.Recent, null -> null
    }
  }

  suspend fun chapters(bookId: BookId): List<MediaItem>? {
    val book = bookRepository.get(bookId) ?: return null
    return playbackItems(book)
  }

  internal fun playbackItems(book: Book): List<MediaItem> {
    return book.playbackItems().map { playbackItem ->
      mediaItem(playbackItem, book.content)
    }
  }

  /**
   * The playback items of [book] starting at the global item index
   * [fromItemIndex]. Appending a partially imported book only needs its new
   * tail; building every item again would multiply with the chapter count.
   */
  internal fun playbackItems(
    book: Book,
    fromItemIndex: Int,
  ): List<MediaItem> {
    return book.playbackItems(fromItemIndex).map { playbackItem ->
      mediaItem(playbackItem, book.content)
    }
  }

  /**
   * A window of playlist items around [centerItemIndex] so a book with
   * thousands of chapters can start without allocating every [MediaItem] on
   * the critical path. [PlaybackWindow.indexInWindow] is the seek index for
   * [androidx.media3.common.Player.setMediaItems]; [absoluteStartIndex] lets
   * the caller expand to the full list without losing the playing chapter.
   */
  internal fun playbackItemsWindow(
    book: Book,
    centerItemIndex: Int,
    radius: Int = PLAYLIST_WINDOW_RADIUS,
  ): PlaybackWindow {
    val total = book.chapters.sumOf { it.chapterMarks.size }
    if (total == 0) {
      return PlaybackWindow(
        items = emptyList(),
        indexInWindow = 0,
        absoluteStartIndex = 0,
        totalItemCount = 0,
      )
    }
    val center = centerItemIndex.coerceIn(0, total - 1)
    if (total <= radius * 2 + 1) {
      val items = playbackItems(book)
      return PlaybackWindow(
        items = items,
        indexInWindow = center,
        absoluteStartIndex = 0,
        totalItemCount = total,
      )
    }
    val from = (center - radius).coerceAtLeast(0)
    val toExclusive = (center + radius + 1).coerceAtMost(total)
    // both bounds matter: from alone would allocate every chapter after [from]
    // for a book resumed near the end
    val items = book.playbackItems(fromItemIndex = from, untilItemIndex = toExclusive)
      .map { mediaItem(it, book.content) }
    return PlaybackWindow(
      items = items,
      indexInWindow = center - from,
      absoluteStartIndex = from,
      totalItemCount = total,
    )
  }

  internal data class PlaybackWindow(
    val items: List<MediaItem>,
    /** Index of the resume chapter inside [items]. */
    val indexInWindow: Int,
    /** Global playlist index of [items].first(). */
    val absoluteStartIndex: Int,
    val totalItemCount: Int,
  ) {
    val isPartial: Boolean
      get() = items.size < totalItemCount
  }

  private companion object {
    /** Chapters kept on either side of the resume point for a fast open. */
    const val PLAYLIST_WINDOW_RADIUS = 24
  }

  /**
   * The media ids of [limit] playback items of [book] starting at global index
   * [fromItemIndex], in playlist order. Used to check that an existing
   * playlist is still a prefix (or a mid-book window) of the book without
   * assembling every item of a big book.
   */
  internal fun playbackItemIds(
    book: Book,
    limit: Int,
    fromItemIndex: Int = 0,
  ): List<String> {
    if (limit <= 0) return emptyList()
    val ids = ArrayList<String>(limit)
    var index = 0
    bookChapters@ for (chapter in book.chapters) {
      for (markIndex in chapter.chapterMarks.indices) {
        if (index >= fromItemIndex + limit) {
          break@bookChapters
        }
        if (index >= fromItemIndex) {
          val mark = chapter.chapterMarks[markIndex]
          val mediaId = MediaId.ChapterMark(
            bookId = book.id,
            chapterId = chapter.id,
            markIndex = markIndex,
            startMs = mark.startMs,
            endMs = mark.endMs,
          )
          ids += Json.encodeToString(MediaId.serializer(), mediaId)
        }
        index++
      }
    }
    return ids
  }

  /** Global playlist index of [mediaId] in [book], or null when not a mark of it. */
  internal fun indexOfPlaybackItem(
    book: Book,
    mediaId: String,
  ): Int? {
    val parsed = mediaId.toMediaIdOrNull() as? MediaId.ChapterMark ?: return null
    if (parsed.bookId != book.id) return null
    var index = 0
    for (chapter in book.chapters) {
      if (chapter.id == parsed.chapterId) {
        if (parsed.markIndex !in chapter.chapterMarks.indices) return null
        return index + parsed.markIndex
      }
      index += chapter.chapterMarks.size
    }
    return null
  }

  suspend fun children(id: String): List<MediaItem>? {
    val mediaId = id.toMediaIdOrNull() ?: return null
    return when (mediaId) {
      MediaId.Root -> {
        bookRepository.all()
          .sortedWith(BookComparator.ByLastPlayed)
          .map { book ->
            mediaItem(book)
          }
      }
      is MediaId.Book -> chapters(mediaId.id)
      is MediaId.Chapter, is MediaId.ChapterMark -> null
      MediaId.Recent -> {
        val bookId = currentBookStoreId.data.first() ?: return null
        val book = bookRepository.get(bookId) ?: return null
        listOf(mediaItem(book))
      }
    }
  }

  fun mediaItem(book: Book): MediaItem = mediaItem(book.content)

  /**
   * Builds the item of a whole book. It only needs the content, so callers that
   * just want to start a book don't have to resolve all of its chapters.
   */
  fun mediaItem(content: BookContent): MediaItem = MediaItem(
    title = content.name,
    mediaId = MediaId.Book(content.id),
    browsable = false,
    isPlayable = true,
    imageUri = content.cover?.toProvidedUri(),
    mediaType = MediaType.AudioBook,
  )

  private fun mediaItem(
    chapter: Chapter,
    content: BookContent,
  ) = MediaItem(
    title = chapter.name ?: chapter.id.value,
    mediaId = MediaId.Chapter(bookId = content.id, chapterId = chapter.id),
    browsable = false,
    isPlayable = true,
    sourceUri = chapter.id.toUri(),
    imageUri = content.cover?.toProvidedUri(),
    artist = content.author,
    mediaType = MediaType.AudioBookChapter,
  )

  private fun mediaItem(
    playbackItem: PlaybackItem,
    content: BookContent,
  ) = MediaItem(
    title = playbackItem.mark.name
      ?: playbackItem.chapter.name
      ?: playbackItem.chapter.id.value,
    mediaId = playbackItem.mediaId,
    browsable = false,
    isPlayable = true,
    sourceUri = playbackItem.chapter.id.toUri(),
    imageUri = content.cover?.toProvidedUri(),
    artist = content.author,
    durationMs = playbackItem.mark.durationMs,
    clippingConfiguration = ClippingConfiguration.Builder()
      .setStartPositionMs(playbackItem.mark.startMs)
      .setEndPositionMs(playbackItem.mark.endMs)
      .build(),
    mediaType = MediaType.AudioBookChapter,
  )

  private fun File.toProvidedUri(): Uri = imageFileProvider.uri(this)
}
