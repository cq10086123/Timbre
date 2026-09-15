package voice.features.folderPicker

import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import voice.core.data.folders.FolderType
import voice.core.ui.icons.VoiceIcons
import voice.core.strings.R as StringsR

@Composable
internal fun FolderTypeIcon(folderType: FolderType) {
  Icon(
    imageVector = folderType.icon(),
    contentDescription = folderType.contentDescription(),
  )
}

private fun FolderType.icon(): ImageVector = when (this) {
  FolderType.SingleFile -> VoiceIcons.AudioFile
  FolderType.SingleFolder,
  FolderType.Root,
  FolderType.Author,
  -> VoiceIcons.Folder
}

@Composable
private fun FolderType.contentDescription(): String {
  val res = when (this) {
    FolderType.SingleFile -> StringsR.string.folder_add_type_file
    FolderType.SingleFolder,
    FolderType.Root,
    FolderType.Author,
    -> StringsR.string.folder_add_type_folder
  }
  return stringResource(res)
}
