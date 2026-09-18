package voice.features.webdav

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.retain.retain
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation3.runtime.NavEntry
import androidx.compose.ui.unit.dp
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides
import voice.core.common.rootGraphAs
import voice.core.ui.icons.VoiceIcons
import voice.navigation.Destination
import voice.navigation.NavEntryProvider
import voice.core.strings.R as StringsR

@ContributesTo(AppScope::class)
interface WebDavServersGraph {
  val webDavServersViewModel: WebDavServersViewModel
}

@ContributesTo(AppScope::class)
interface WebDavServersProvider {

  @Provides
  @IntoSet
  fun webDavServersNavEntryProvider(): NavEntryProvider<*> = NavEntryProvider<Destination.WebDavServers> { key ->
    NavEntry(key) {
      WebDavServersScreen()
    }
  }
}

@Composable
fun WebDavServersScreen() {
  val viewModel = retain<WebDavServersViewModel> {
    rootGraphAs<WebDavServersGraph>()
      .webDavServersViewModel
  }
  val viewState = viewModel.viewState()
  WebDavServersView(
    viewState = viewState,
    listener = viewModel,
  )
  viewState.dialog?.let { dialog ->
    WebDavServerDialogView(
      dialog = dialog,
      listener = viewModel,
    )
  }
  viewState.deleteCandidate?.let { _ ->
    WebDavDeleteConfirmView(listener = viewModel)
  }
}

@Composable
private fun WebDavServersView(
  viewState: WebDavServersViewState,
  listener: WebDavServersViewModel,
) {
  Scaffold(
    topBar = {
      TopAppBar(
        title = {
          Text(stringResource(StringsR.string.webdav_title))
        },
        navigationIcon = {
          IconButton(onClick = listener::back) {
            Icon(imageVector = VoiceIcons.ArrowBack, contentDescription = stringResource(StringsR.string.common_action_close))
          }
        },
        actions = {
          IconButton(onClick = listener::addServer) {
            Icon(imageVector = VoiceIcons.Add, contentDescription = stringResource(StringsR.string.webdav_server_add))
          }
        },
      )
    },
  ) { contentPadding ->
    if (viewState.servers.isEmpty()) {
      Text(
        modifier = Modifier
          .padding(contentPadding)
          .padding(24.dp),
        text = stringResource(StringsR.string.webdav_empty),
      )
    } else {
      LazyColumn(contentPadding = contentPadding) {
        item {
          ListItem(
            modifier = Modifier.clickable(onClick = listener::openCacheSettings),
          ) {
            Column {
              Text(stringResource(StringsR.string.webdav_cache_title))
              Text(stringResource(StringsR.string.webdav_cache_settings_summary))
            }
          }
        }
        items(viewState.servers, key = { it.id }) { server ->
          ListItem(
            modifier = Modifier.clickable { listener.editServer(server) },
            trailingContent = {
              Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { listener.browse(server) }) {
                  Icon(imageVector = VoiceIcons.Folder, contentDescription = stringResource(StringsR.string.webdav_browse))
                }
                IconButton(onClick = { listener.requestDelete(server) }) {
                  Icon(imageVector = VoiceIcons.Delete, contentDescription = stringResource(StringsR.string.webdav_server_delete))
                }
              }
            },
          ) {
            Column {
              Text(server.name)
              Text(server.baseUrl)
            }
          }
        }
      }
    }
  }
}

@Composable
private fun WebDavDeleteConfirmView(listener: WebDavServersViewModel) {
  AlertDialog(
    onDismissRequest = listener::dismissDelete,
    title = {
      Text(stringResource(StringsR.string.webdav_server_delete))
    },
    text = {
      Text(stringResource(StringsR.string.webdav_delete_server_message))
    },
    confirmButton = {
      TextButton(onClick = listener::confirmDelete) {
        Text(stringResource(StringsR.string.webdav_delete))
      }
    },
    dismissButton = {
      TextButton(onClick = listener::dismissDelete) {
        Text(stringResource(StringsR.string.common_dialog_cancel))
      }
    },
  )
}

@Composable
private fun WebDavServerDialogView(
  dialog: WebDavServerDialog,
  listener: WebDavServersViewModel,
) {
  AlertDialog(
    onDismissRequest = listener::dismissDialog,
    title = {
      Text(
        stringResource(
          if (dialog.existingId == null) StringsR.string.webdav_server_add else StringsR.string.webdav_server_edit,
        ),
      )
    },
    text = {
      Column {
        OutlinedTextField(
          modifier = Modifier.fillMaxWidth(),
          value = dialog.name,
          onValueChange = { value -> listener.updateDialog { it.copy(name = value) } },
          label = { Text(stringResource(StringsR.string.webdav_server_name)) },
          singleLine = true,
        )
        Spacer(Modifier.size(4.dp))
        OutlinedTextField(
          modifier = Modifier.fillMaxWidth(),
          value = dialog.url,
          onValueChange = { value -> listener.updateDialog { it.copy(url = value) } },
          label = { Text(stringResource(StringsR.string.webdav_server_url)) },
          singleLine = true,
        )
        Spacer(Modifier.size(4.dp))
        OutlinedTextField(
          modifier = Modifier.fillMaxWidth(),
          value = dialog.username,
          onValueChange = { value -> listener.updateDialog { it.copy(username = value) } },
          label = { Text(stringResource(StringsR.string.webdav_server_username)) },
          singleLine = true,
        )
        Spacer(Modifier.size(4.dp))
        OutlinedTextField(
          modifier = Modifier.fillMaxWidth(),
          value = dialog.password,
          onValueChange = { value -> listener.updateDialog { it.copy(password = value) } },
          label = {
            Text(
              stringResource(
                if (dialog.existingId == null) StringsR.string.webdav_server_password else StringsR.string.webdav_server_password_keep,
              ),
            )
          },
          singleLine = true,
        )
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(Modifier.weight(1F)) {
            Text(stringResource(StringsR.string.webdav_server_trust_all))
            Text(
              text = stringResource(StringsR.string.webdav_server_trust_all_summary),
              style = MaterialTheme.typography.bodySmall,
            )
          }
          Switch(
            checked = dialog.trustAllCertificates,
            onCheckedChange = { value -> listener.updateDialog { it.copy(trustAllCertificates = value) } },
          )
        }
        Spacer(Modifier.size(8.dp))
        dialog.testOutcome?.let { outcome ->
          Text(
            text = stringResource(outcome.messageRes()),
            color = if (outcome == WebDavTestOutcome.Success) {
              MaterialTheme.colorScheme.primary
            } else {
              MaterialTheme.colorScheme.error
            },
          )
        }
        Text(
          modifier = Modifier.padding(top = 4.dp),
          text = stringResource(StringsR.string.webdav_http_hint),
          style = MaterialTheme.typography.bodySmall,
        )
      }
    },
    confirmButton = {
      Row(verticalAlignment = Alignment.CenterVertically) {
        if (dialog.testing) {
          CircularProgressIndicator(modifier = Modifier.size(24.dp))
          Spacer(Modifier.size(8.dp))
        }
        TextButton(onClick = listener::testConnection, enabled = !dialog.testing) {
          Text(stringResource(StringsR.string.webdav_test_connection))
        }
        Spacer(Modifier.size(8.dp))
        Button(onClick = listener::save) {
          Text(stringResource(StringsR.string.webdav_save))
        }
      }
    },
    dismissButton = {
      TextButton(onClick = listener::dismissDialog) {
        Text(stringResource(StringsR.string.common_dialog_cancel))
      }
    },
  )
}

private fun WebDavTestOutcome.messageRes(): Int {
  return when (this) {
    WebDavTestOutcome.Success -> StringsR.string.webdav_test_ok
    WebDavTestOutcome.RangeMissing -> StringsR.string.webdav_test_range_missing
    WebDavTestOutcome.AuthError -> StringsR.string.webdav_test_auth_failed
    WebDavTestOutcome.CertificateError -> StringsR.string.webdav_test_cert_failed
    WebDavTestOutcome.NetworkError -> StringsR.string.webdav_test_network_failed
    WebDavTestOutcome.InvalidAddress -> StringsR.string.webdav_test_invalid_address
    WebDavTestOutcome.SaveError -> StringsR.string.webdav_error
    WebDavTestOutcome.MissingPassword -> StringsR.string.webdav_server_password
  }
}
