package voice.features.webdav

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import voice.core.ui.icons.VoiceIcons
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** One settings entry: icon, title, and a line explaining what it does. */
@Composable
internal fun SettingRow(
  icon: ImageVector,
  title: String,
  subtitle: String,
  onClick: () -> Unit,
  enabled: Boolean = true,
) {
  ListItem(
    modifier = Modifier.clickable(enabled = enabled, onClick = onClick),
    leadingContent = {
      Icon(imageVector = icon, contentDescription = title)
    },
    supportingContent = {
      Text(subtitle)
    },
  ) {
    Text(title)
  }
}

/**
 * One pickable entry - a backup, a server. A flat row with a hairline divider,
 * not a filled card: the dialogs sit on the dark player surface, where stacked
 * boxes read as separate panels instead of one list.
 */
@Composable
internal fun ChoiceRow(
  title: String,
  subtitle: String,
  selected: Boolean,
  showDivider: Boolean,
  onClick: () -> Unit,
) {
  Column(modifier = Modifier.fillMaxWidth()) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .clip(MaterialTheme.shapes.small)
        .clickable(onClick = onClick)
        .padding(horizontal = 4.dp, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = title,
          style = MaterialTheme.typography.bodyLarge,
          color = if (selected) {
            MaterialTheme.colorScheme.primary
          } else {
            MaterialTheme.colorScheme.onSurface
          },
        )
        if (subtitle.isNotEmpty()) {
          Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
      if (selected) {
        Icon(
          imageVector = VoiceIcons.Check,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.primary,
          modifier = Modifier.padding(start = 12.dp),
        )
      }
    }
    if (showDivider) {
      HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    }
  }
}

/** "2026-10-23 17:05 · 92 KB", dropping whichever half is unknown. */
internal fun backupMeta(
  lastModified: Long,
  size: Long,
): String {
  val time = if (lastModified > 0L) {
    TIME_FORMATTER.format(Instant.ofEpochMilli(lastModified))
  } else {
    ""
  }
  val amount = if (size > 0L) "${(size + 1023L) / 1024L} KB" else ""
  return listOf(time, amount).filter { it.isNotEmpty() }.joinToString(" · ")
}

private val TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter
  .ofPattern("yyyy-MM-dd HH:mm")
  .withZone(ZoneId.systemDefault())
