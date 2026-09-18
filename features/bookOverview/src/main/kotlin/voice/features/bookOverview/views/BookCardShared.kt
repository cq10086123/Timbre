package voice.features.bookOverview.views

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import voice.core.data.BookId
import voice.core.scanner.BookScanError
import voice.core.scanner.BookScanProgress
import voice.core.strings.R as StringsR

@Composable
internal fun BookCard(
  bookId: BookId,
  onBookClick: (BookId) -> Unit,
  onBookLongClick: (BookId) -> Unit,
  modifier: Modifier = Modifier,
  content: @Composable () -> Unit,
) {
  ElevatedCard(
    shape = MaterialTheme.shapes.extraLarge,
    modifier = modifier
      .fillMaxWidth()
      .combinedClickable(
        onClick = { onBookClick(bookId) },
        onLongClick = { onBookLongClick(bookId) },
      ),
  ) {
    Box {
      content()
      if (bookId.toUri().scheme.equals("http", ignoreCase = true) ||
        bookId.toUri().scheme.equals("https", ignoreCase = true)
      ) {
        val remoteLabel = stringResource(StringsR.string.webdav_title)
        Surface(
          modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(8.dp)
            .size(28.dp)
            .semantics {
              contentDescription = remoteLabel
            },
          shape = CircleShape,
          color = MaterialTheme.colorScheme.primaryContainer,
          contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ) {
          Text(
            text = "☁",
            modifier = Modifier.fillMaxSize().padding(top = 1.dp),
            textAlign = TextAlign.Center,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
          )
        }
      }
    }
  }
}

@Composable
internal fun BookRemainingProgressRow(
  remainingTime: String,
  progress: Float,
  modifier: Modifier = Modifier,
  remainingTimeMaxLines: Int = Int.MAX_VALUE,
  progressMaxLines: Int = Int.MAX_VALUE,
) {
  Row(
    modifier = modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = remainingTime,
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      maxLines = remainingTimeMaxLines,
    )
    if (progress > 0f) {
      Text(
        text = "${(progress * 100).toInt()}%",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = progressMaxLines,
      )
    }
  }
}

@Composable
internal fun BookProgressIndicator(
  progress: Float,
  modifier: Modifier = Modifier,
  color: Color? = null,
  trackColor: Color? = null,
) {
  if (progress > 0.05f) {
    if (color != null && trackColor != null) {
      LinearProgressIndicator(
        progress = { progress },
        modifier = modifier,
        color = color,
        trackColor = trackColor,
      )
    } else {
      LinearProgressIndicator(
        progress = { progress },
        modifier = modifier,
      )
    }
  }
}

/**
 * The slim import progress line that is pinned to the top edge of a book card
 * while the book is still being imported.
 *
 * The line is inset from the card edges because the card's large corner radius
 * would otherwise clip the line at both ends and hide it. While the chapter
 * total is still unknown (the directory listing hasn't finished), the line is
 * indeterminate.
 */
@Composable
internal fun BookImportProgressLine(
  progress: BookScanProgress,
  modifier: Modifier = Modifier,
) {
  val lineModifier = modifier
    .fillMaxWidth()
    .padding(start = 12.dp, end = 12.dp, top = 10.dp)
    .height(4.dp)
    .clip(MaterialTheme.shapes.small)
  if (progress.chaptersTotal == 0) {
    LinearProgressIndicator(modifier = lineModifier)
  } else {
    LinearProgressIndicator(
      progress = { progress.fraction },
      modifier = lineModifier,
    )
  }
}

/**
 * The error line of a book card: the import of the book failed (or only
 * partially succeeded) and the user can retry it right there. Without it a
 * failing import was only visible in the log, while the book either never
 * showed up on the shelf or its import placeholder vanished.
 */
@Composable
internal fun BookImportErrorRow(
  error: BookScanError,
  onRetry: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val message = when (error.kind) {
    BookScanError.Kind.Unreachable -> stringResource(StringsR.string.library_import_error_unreachable)
    BookScanError.Kind.AnalysisFailed -> if (error.failedChapters > 0) {
      pluralStringResource(
        StringsR.plurals.library_import_error_partial,
        error.failedChapters,
        error.failedChapters,
      )
    } else {
      stringResource(StringsR.string.library_import_error_analysis_failed)
    }
  }
  Row(
    modifier = modifier,
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.SpaceBetween,
  ) {
    Text(
      text = message,
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.error,
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.weight(1f, fill = false),
    )
    TextButton(onClick = onRetry) {
      Text(
        text = stringResource(StringsR.string.library_import_error_retry),
        style = MaterialTheme.typography.labelMedium,
        maxLines = 1,
      )
    }
  }
}

/** The "importing x of y chapters" caption of a book card. */
@Composable
internal fun BookImportProgressText(
  progress: BookScanProgress,
  modifier: Modifier = Modifier,
) {
  val text = if (progress.chaptersTotal == 0) {
    stringResource(StringsR.string.library_import_preparing)
  } else {
    pluralStringResource(
      StringsR.plurals.library_import_progress,
      progress.chaptersTotal,
      progress.chaptersScanned,
      progress.chaptersTotal,
    )
  }
  Text(
    text = text,
    style = MaterialTheme.typography.labelMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    maxLines = 1,
    overflow = TextOverflow.Ellipsis,
    modifier = modifier,
  )
}
