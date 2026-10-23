package io.github.tendo505.tandem.app.ui

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.github.tendo505.tandem.app.R
import io.github.tendo505.tandem.app.sync.SyncProblem
import io.github.tendo505.tandem.app.sync.SyncStatus
import io.github.tendo505.tandem.core.Limits

/** One line saying whether this phone is in step with everyone else. */
@Composable
fun SyncBanner(status: SyncStatus, pending: Int, modifier: Modifier = Modifier) {
    val (icon, text) = when {
        status.problem == SyncProblem.NOT_CONFIGURED -> Icons.Default.CloudOff to stringResource(R.string.status_not_configured)
        status.problem == SyncProblem.OFFLINE -> Icons.Default.CloudOff to stringResource(R.string.status_offline)
        status.problem == SyncProblem.REJECTED -> Icons.Default.ErrorOutline to stringResource(R.string.status_rejected, status.detail ?: "?")
        status.lastSuccessAt != null -> Icons.Default.CloudDone to stringResource(R.string.status_synced, relativeTime(status.lastSuccessAt))
        status.running -> Icons.Default.Sync to stringResource(R.string.status_syncing)
        else -> Icons.Default.Cloud to stringResource(R.string.status_never)
    }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = modifier.fillMaxWidth().testTag("sync-banner")) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            Column {
                Text(text, style = MaterialTheme.typography.bodySmall)
                if (pending > 0) {
                    Text(pluralStringResource(R.plurals.unsynced, pending, pending), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Composable
private fun relativeTime(at: Long): String {
    val now = System.currentTimeMillis()
    return if (now - at < DateUtils.MINUTE_IN_MILLIS) stringResource(R.string.just_now)
    else DateUtils.getRelativeTimeSpanString(at, now, DateUtils.MINUTE_IN_MILLIS).toString()
}

/** A dialog with one text field. [error] returns a message to show instead of confirming, or null if the input is fine. */
@Composable
fun TextInputDialog(
    title: String,
    label: String,
    confirm: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    initial: String = "",
    help: String? = null,
    error: (String) -> String? = { null },
) {
    var text by rememberSaveable { mutableStateOf(initial) }
    var problem by rememberSaveable { mutableStateOf<String?>(null) }
    val submit = {
        problem = error(text)
        if (problem == null && text.isNotBlank()) onConfirm(text.trim())
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(Limits.MAX_TEXT); problem = null },
                label = { Text(label) },
                singleLine = true,
                isError = problem != null,
                supportingText = (problem ?: help)?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { submit() }),
                modifier = Modifier.fillMaxWidth().testTag("dialog-input"),
            )
        },
        confirmButton = { TextButton(onClick = submit, enabled = text.isNotBlank()) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
