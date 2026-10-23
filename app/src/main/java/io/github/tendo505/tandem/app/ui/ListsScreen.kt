package io.github.tendo505.tandem.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.tendo505.tandem.app.R
import io.github.tendo505.tandem.app.data.ListSummary
import io.github.tendo505.tandem.app.data.TandemRepository

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListsScreen(vm: ListsViewModel, open: (String) -> Unit, openSettings: () -> Unit) {
    val lists by vm.lists.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    var creating by rememberSaveable { mutableStateOf(false) }
    var joining by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = { IconButton(onClick = openSettings) { Icon(Icons.Default.Settings, stringResource(R.string.settings)) } },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { creating = true },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text(stringResource(R.string.new_list)) },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            SyncBanner(status, pending = lists.orEmpty().sumOf { it.pending })
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    OutlinedButton(onClick = { joining = true }) {
                        Icon(Icons.Default.GroupAdd, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.join_list))
                    }
                }
                if (lists?.isEmpty() == true) item { EmptyLists() }
                items(lists.orEmpty(), key = { it.id }) { ListCard(it, onClick = { open(it.id) }) }
                item { Spacer(Modifier.height(72.dp)) } // room for the FAB
            }
        }
    }

    if (creating) {
        TextInputDialog(
            title = stringResource(R.string.new_list),
            label = stringResource(R.string.list_name),
            confirm = stringResource(R.string.create),
            onDismiss = { creating = false },
            onConfirm = { creating = false; vm.create(it, open) },
        )
    }
    if (joining) {
        val invalid = stringResource(R.string.join_invalid)
        TextInputDialog(
            title = stringResource(R.string.join_list),
            label = stringResource(R.string.list_code_label),
            confirm = stringResource(R.string.join),
            help = stringResource(R.string.join_hint),
            onDismiss = { joining = false },
            error = { text -> invalid.takeIf { _ -> TandemRepository.parseCode(text) == null } },
            onConfirm = { text -> joining = false; vm.join(text, open) },
        )
    }
}

@Composable
private fun ListCard(list: ListSummary, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.AutoMirrored.Filled.List, null, tint = MaterialTheme.colorScheme.primary)
                Text(
                    list.title.ifBlank { stringResource(R.string.untitled) },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                if (list.total == 0) stringResource(R.string.no_items) else stringResource(R.string.progress, list.done, list.total),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (list.total > 0) {
                LinearProgressIndicator(progress = { list.done.toFloat() / list.total }, modifier = Modifier.fillMaxWidth())
            }
            if (list.pending > 0) {
                Text(pluralStringResource(R.plurals.unsynced, list.pending, list.pending), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun EmptyLists() {
    Column(Modifier.fillMaxWidth().padding(vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.empty_lists_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.empty_lists_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
