package io.github.tendo505.tandem.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.tendo505.tandem.app.R
import io.github.tendo505.tandem.app.Settings
import io.github.tendo505.tandem.app.sync.SyncEngine
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(settings: Settings, sync: SyncEngine, back: () -> Unit) {
    val saved by settings.serverUrl.collectAsStateWithLifecycle()
    val status by sync.status.collectAsStateWithLifecycle()
    var url by rememberSaveable { mutableStateOf(saved) }
    val scope = rememberCoroutineScope()
    val clean = url.trim().trimEnd('/')
    val valid = clean.isEmpty() || Settings.URL.matches(clean)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text(stringResource(R.string.server_url)) },
                singleLine = true,
                isError = !valid,
                supportingText = { Text(stringResource(if (valid) R.string.server_url_help else R.string.server_url_invalid)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    enabled = valid && clean != saved,
                    onClick = { settings.setServerUrl(clean); scope.launch { sync.syncAll() } },
                ) { Text(stringResource(R.string.save)) }
                OutlinedButton(enabled = saved.isNotEmpty(), onClick = { scope.launch { sync.syncAll() } }) {
                    Text(stringResource(R.string.sync_now))
                }
            }
            SyncBanner(status, pending = 0)
            Text(stringResource(R.string.how_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.how_body), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.device_id, settings.nodeId), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
