package voice.features.sourceManager

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.retain.retain
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavEntry
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides
import voice.core.common.rootGraphAs
import voice.core.source.InstalledJdrPackage
import voice.core.strings.R as StringsR
import voice.core.ui.icons.VoiceIcons
import voice.navigation.Destination
import voice.navigation.NavEntryProvider

@ContributesTo(AppScope::class)
interface SourceManagerGraph {
  val sourceManagerViewModelFactory: SourceManagerViewModel.Factory
}

@ContributesTo(AppScope::class)
interface SourceManagerNavEntryProvider {

  @Provides
  @IntoSet
  fun sourceManagerNavEntryProvider(): NavEntryProvider<*> =
    NavEntryProvider<Destination.SourceManager> { key ->
      NavEntry(key) {
        SourceManagerScreen()
      }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceManagerScreen() {
  val viewModel = retain {
    rootGraphAs<SourceManagerGraph>()
      .sourceManagerViewModelFactory
      .create()
  }
  val viewState = viewModel.viewState()
  val listener = viewModel as SourceManagerListener

  val context = LocalContext.current
  val snackbarHostState = remember { SnackbarHostState() }
  val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()

  val filePicker = rememberLauncherForActivityResult(
    ActivityResultContracts.OpenDocument(),
  ) { uri: Uri? ->
    if (uri != null) {
      val bytes = runCatching {
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
      }.getOrNull()
      if (bytes != null) {
        viewModel.importFileBytes(bytes, uri.lastPathSegment ?: "import.jdr")
      }
    }
  }

  LaunchedEffect(viewState.message) {
    viewState.message?.let {
      snackbarHostState.showSnackbar(it)
      listener.dismissMessage()
    }
  }

  Scaffold(
    modifier = Modifier
      .fillMaxSize()
      .nestedScroll(scrollBehavior.nestedScrollConnection),
    snackbarHost = { SnackbarHost(snackbarHostState) },
    topBar = {
      TopAppBar(
        title = { Text(stringResource(StringsR.string.source_manager_title)) },
        navigationIcon = {
          IconButton(onClick = listener::close) {
            Icon(imageVector = VoiceIcons.ArrowBack, contentDescription = stringResource(StringsR.string.common_action_close))
          }
        },
        actions = {
          if (viewState.busy) {
            CircularProgressIndicator(modifier = Modifier.padding(12.dp))
          }
          IconButton(onClick = listener::openImportDialog, enabled = !viewState.busy) {
            Icon(imageVector = VoiceIcons.Language, contentDescription = stringResource(StringsR.string.source_manager_import_url))
          }
          IconButton(
            onClick = { filePicker.launch(arrayOf("*/*")) },
            enabled = !viewState.busy,
          ) {
            Icon(imageVector = VoiceIcons.Folder, contentDescription = stringResource(StringsR.string.source_manager_import_file))
          }
        },
        scrollBehavior = scrollBehavior,
      )
    },
  ) { contentPadding ->
    if (viewState.packages.isEmpty()) {
      Text(
        modifier = Modifier
          .padding(contentPadding)
          .padding(24.dp),
        text = stringResource(StringsR.string.source_manager_empty),
      )
    } else {
      LazyColumn(contentPadding = contentPadding) {
        items(viewState.packages, key = { it.manifest.packageId }) { pkg ->
          PackageItem(
            pkg = pkg,
            listener = listener,
          )
        }
      }
    }
  }

  if (viewState.urlDialog) {
    UrlImportDialog(
      onConfirm = listener::confirmImport,
      onDismiss = listener::dismissImportDialog,
    )
  }

  viewState.deleteCandidate?.let { candidate ->
    AlertDialog(
      onDismissRequest = listener::dismissDelete,
      title = { Text(stringResource(StringsR.string.source_manager_delete_title)) },
      text = {
        Text(stringResource(StringsR.string.source_manager_delete_message, candidate.manifest.name))
      },
      confirmButton = {
        TextButton(onClick = listener::confirmDelete) {
          Text(stringResource(StringsR.string.common_action_delete))
        }
      },
      dismissButton = {
        TextButton(onClick = listener::dismissDelete) {
          Text(stringResource(StringsR.string.common_dialog_cancel))
        }
      },
    )
  }
}

@Composable
private fun PackageItem(
  pkg: InstalledJdrPackage,
  listener: SourceManagerListener,
) {
  ListItem(
    modifier = Modifier.clickable { listener.requestDelete(pkg) },
    supportingContent = {
      Column {
        val version = pkg.manifest.version.takeIf { it.isNotBlank() }
        val origin = pkg.origin.takeIf { it.isNotBlank() }
        listOfNotNull(
          version?.let { stringResource(StringsR.string.source_manager_version, it) },
          origin,
        ).forEach { line ->
          Text(line, style = MaterialTheme.typography.bodySmall)
        }
        if (pkg.lastError.isNotBlank()) {
          Text(
            text = pkg.lastError,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
          )
        }
        pkg.manifest.sources.forEach { source ->
          val enabled = pkg.sourceEnabled[source.id] ?: pkg.enabled
          Row(modifier = Modifier.fillMaxWidth()) {
            Text(
              text = source.name,
              modifier = Modifier.weight(1f),
              style = MaterialTheme.typography.bodySmall,
            )
            Switch(
              checked = enabled,
              onCheckedChange = { checked ->
                listener.toggleSource(pkg, source.id, checked)
              },
            )
          }
        }
      }
    },
    trailingContent = {
      Switch(
        checked = pkg.enabled,
        onCheckedChange = { checked -> listener.togglePackage(pkg, checked) },
      )
    },
  ) {
    Text(pkg.manifest.name)
  }
}

@Composable
private fun UrlImportDialog(
  onConfirm: (String) -> Unit,
  onDismiss: () -> Unit,
) {
  var url by remember { mutableStateOf("") }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(StringsR.string.source_manager_import_url)) },
    text = {
      OutlinedTextField(
        value = url,
        onValueChange = { url = it },
        singleLine = true,
        placeholder = { Text(stringResource(StringsR.string.source_manager_import_url_hint)) },
        modifier = Modifier.fillMaxWidth(),
      )
    },
    confirmButton = {
      Button(onClick = { onConfirm(url) }, enabled = url.isNotBlank()) {
        Text(stringResource(StringsR.string.common_dialog_confirm))
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text(stringResource(StringsR.string.common_dialog_cancel))
      }
    },
  )
}
