package voice.features.webdav

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.retain.retain
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavEntry
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides
import voice.core.common.rootGraphAs
import voice.core.ui.icons.VoiceIcons
import voice.navigation.Destination
import voice.navigation.NavEntryProvider
import voice.navigation.Origin
import voice.core.strings.R as StringsR

@ContributesTo(AppScope::class)
interface WebDavBackupGraph {
  val webDavBackupViewModelFactory: WebDavBackupViewModel.Factory
}

@ContributesTo(AppScope::class)
interface WebDavBackupProvider {

  @Provides
  @IntoSet
  fun webDavBackupNavEntryProvider(): NavEntryProvider<*> = NavEntryProvider<Destination.WebDavBackup> { key ->
    NavEntry(key) {
      WebDavBackupScreen(origin = key.origin)
    }
  }
}

/** Where the shelf is backed up to, and where it is restored from. */
@Composable
fun WebDavBackupScreen(origin: Origin) {
  val viewModel = retain(origin.name) {
    rootGraphAs<WebDavBackupGraph>()
      .webDavBackupViewModelFactory
      .create(origin)
  }
  val viewState = viewModel.viewState()
  WebDavBackupView(
    viewState = viewState,
    listener = viewModel,
  )
}

@Composable
private fun WebDavBackupView(
  viewState: WebDavBackupViewState,
  listener: WebDavBackupViewModel,
) {
  // resolved before LazyColumn: its content lambda is not composable
  val status = webDavBackupMessage(viewState.backupState)
  val restoreDone = viewState.backupState as? WebDavBackupUiState.RestoreDone
  // books of the backup that sit on a webdav server and are not imported here
  // cannot get their progress back in this run, so say what to do about it
  val missingWebDavHint = if (restoreDone != null && restoreDone.missingWebDav > 0) {
    stringResource(
      StringsR.string.settings_webdav_restore_missing_webdav,
      restoreDone.missingWebDav,
    )
  } else {
    null
  }
  val busy = viewState.backupState == WebDavBackupUiState.Busy
  val location = viewState.location
  val folderSummary = if (location == null) {
    stringResource(StringsR.string.webdav_backup_folder_not_set)
  } else {
    "${location.serverName} · ${viewState.folder}"
  }
  val defaultLabel = stringResource(StringsR.string.webdav_backup_default_label)
  val restoreRows = restoreRows(viewState.restorePicker, defaultLabel)

  Scaffold(
    topBar = {
      TopAppBar(
        title = {
          Text(stringResource(StringsR.string.webdav_section_backup))
        },
        navigationIcon = {
          IconButton(onClick = listener::back) {
            Icon(
              imageVector = VoiceIcons.ArrowBack,
              contentDescription = stringResource(StringsR.string.common_action_close),
            )
          }
        },
      )
    },
  ) { contentPadding ->
    LazyColumn(contentPadding = contentPadding) {
      item {
        SettingRow(
          icon = VoiceIcons.Folder,
          title = stringResource(StringsR.string.webdav_backup_folder_title),
          subtitle = folderSummary,
          onClick = listener::openBackupFolder,
        )
      }
      item {
        SettingRow(
          icon = VoiceIcons.Sync,
          title = stringResource(StringsR.string.settings_webdav_backup_title),
          subtitle = stringResource(StringsR.string.settings_webdav_backup_summary),
          onClick = listener::backup,
          enabled = !busy,
        )
      }
      item {
        SettingRow(
          icon = VoiceIcons.History,
          title = stringResource(StringsR.string.settings_webdav_restore_title),
          subtitle = stringResource(StringsR.string.settings_webdav_restore_summary),
          onClick = listener::restore,
          enabled = !busy,
        )
      }
      status?.let { message ->
        item {
          Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
          )
        }
      }
      missingWebDavHint?.let { hint ->
        item {
          Text(
            text = hint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
          )
        }
      }
    }
  }

  viewState.backupDialog?.let { dialog ->
    BackupLabelDialog(
      dialog = dialog,
      defaultLabel = defaultLabel,
      onLabelChange = listener::backupLabelChanged,
      onConfirm = listener::confirmBackup,
      onDismiss = listener::dismissBackupDialog,
    )
  }
  viewState.restorePicker?.let { picker ->
    RestorePickerDialog(
      picker = picker,
      rows = restoreRows,
      onSelect = listener::restoreBackup,
      onDismiss = listener::dismissRestorePicker,
    )
  }
  if (viewState.noFolderPrompt) {
    NoFolderDialog(
      onPickFolder = listener::pickFolderFromPrompt,
      onDismiss = listener::dismissNoFolderPrompt,
    )
  }
}

@Composable
private fun NoFolderDialog(
  onPickFolder: () -> Unit,
  onDismiss: () -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = {
      Text(stringResource(StringsR.string.webdav_no_folder_title))
    },
    text = {
      Text(stringResource(StringsR.string.webdav_no_folder_message))
    },
    confirmButton = {
      TextButton(onClick = onPickFolder) {
        Text(stringResource(StringsR.string.webdav_no_folder_action))
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text(stringResource(StringsR.string.common_action_cancel))
      }
    },
  )
}

@Composable
private fun BackupLabelDialog(
  dialog: WebDavBackupViewState.BackupDialog,
  defaultLabel: String,
  onLabelChange: (String) -> Unit,
  onConfirm: () -> Unit,
  onDismiss: () -> Unit,
) {
  // tapping a row fills the field instead of backing up right away, so the
  // user sees which backup is about to be overwritten
  val rows = dialog.existing.map { item ->
    BackupLabelRow(
      label = item.label.orEmpty(),
      title = item.label ?: defaultLabel,
      subtitle = backupMeta(item.lastModified, item.size),
    )
  }
  val chosen = dialog.label.trim()
  AlertDialog(
    onDismissRequest = onDismiss,
    title = {
      Text(stringResource(StringsR.string.webdav_backup_dialog_title))
    },
    text = {
      // one scroll container for the whole body: a nested list would fight it
      // for the gesture, and a label list longer than the screen has to stay
      // reachable
      Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
        OutlinedTextField(
          value = dialog.label,
          onValueChange = onLabelChange,
          singleLine = true,
          placeholder = {
            Text(stringResource(StringsR.string.webdav_backup_dialog_hint))
          },
          modifier = Modifier.fillMaxWidth(),
        )
        if (dialog.loadingExisting) {
          Text(
            text = stringResource(StringsR.string.webdav_backup_existing_loading),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 12.dp),
          )
        } else if (rows.isNotEmpty()) {
          Text(
            text = stringResource(StringsR.string.webdav_backup_existing_title),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
          )
          rows.forEachIndexed { index, row ->
            ChoiceRow(
              title = row.title,
              subtitle = row.subtitle,
              selected = row.label == chosen,
              showDivider = index < rows.lastIndex,
              onClick = { onLabelChange(row.label) },
            )
          }
        }
        Text(
          text = stringResource(StringsR.string.webdav_backup_dialog_note),
          style = MaterialTheme.typography.bodySmall,
          modifier = Modifier.padding(top = 8.dp),
        )
      }
    },
    confirmButton = {
      TextButton(onClick = onConfirm) {
        Text(stringResource(StringsR.string.webdav_backup_dialog_confirm))
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text(stringResource(StringsR.string.common_action_cancel))
      }
    },
  )
}

private data class BackupLabelRow(
  /** The label the backup file carries; empty for the default backup. */
  val label: String,
  val title: String,
  val subtitle: String,
)

@Composable
private fun RestorePickerDialog(
  picker: WebDavBackupViewState.RestorePicker,
  rows: List<RestoreRow>,
  onSelect: (String?) -> Unit,
  onDismiss: () -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = {
      Text(stringResource(StringsR.string.webdav_restore_picker_title))
    },
    text = {
      when (picker) {
        WebDavBackupViewState.RestorePicker.Loading -> {
          CircularProgressIndicator()
        }
        is WebDavBackupViewState.RestorePicker.Failed -> {
          Text(picker.reason ?: stringResource(StringsR.string.webdav_restore_picker_failed))
        }
        is WebDavBackupViewState.RestorePicker.Loaded -> {
          if (rows.isEmpty()) {
            Text(stringResource(StringsR.string.webdav_restore_picker_empty))
          } else {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
              rows.forEachIndexed { index, row ->
                ChoiceRow(
                  title = row.title,
                  subtitle = row.subtitle,
                  selected = false,
                  showDivider = index < rows.lastIndex,
                  onClick = { onSelect(row.label) },
                )
              }
            }
          }
        }
      }
    },
    confirmButton = {
      TextButton(onClick = onDismiss) {
        Text(stringResource(StringsR.string.common_action_cancel))
      }
    },
  )
}

private data class RestoreRow(
  val title: String,
  val subtitle: String,
  val label: String?,
)

@Composable
private fun restoreRows(
  picker: WebDavBackupViewState.RestorePicker?,
  defaultLabel: String,
): List<RestoreRow> {
  val loaded = picker as? WebDavBackupViewState.RestorePicker.Loaded ?: return emptyList()
  return loaded.items.map { item ->
    RestoreRow(
      title = item.label ?: defaultLabel,
      subtitle = backupMeta(item.lastModified, item.size),
      label = item.label,
    )
  }
}

@Composable
private fun webDavBackupMessage(state: WebDavBackupUiState): String? {
  return when (state) {
    WebDavBackupUiState.Idle -> null
    WebDavBackupUiState.Busy -> stringResource(StringsR.string.settings_webdav_backup_running)
    is WebDavBackupUiState.BackupDone -> {
      val base = stringResource(
        StringsR.string.settings_webdav_backup_done,
        state.books,
        state.server,
      )
      if (state.label == null) base else "$base · ${state.label}"
    }
    is WebDavBackupUiState.RestoreDone -> {
      val base = stringResource(
        StringsR.string.settings_webdav_restore_done,
        state.restored,
        state.total,
        state.server,
      )
      if (state.label == null) base else "$base · ${state.label}"
    }
    WebDavBackupUiState.NoServer -> stringResource(StringsR.string.settings_webdav_backup_no_server)
    WebDavBackupUiState.NoFolder -> stringResource(StringsR.string.settings_webdav_backup_no_folder)
    WebDavBackupUiState.EmptyShelf -> stringResource(StringsR.string.settings_webdav_backup_empty_shelf)
    is WebDavBackupUiState.BooksLoading -> stringResource(
      StringsR.string.settings_webdav_backup_books_loading,
      state.count,
    )
    WebDavBackupUiState.NoBackupFile -> stringResource(StringsR.string.settings_webdav_restore_no_file)
    is WebDavBackupUiState.Failed ->
      stringResource(StringsR.string.settings_webdav_backup_failed, state.reason)
  }
}
