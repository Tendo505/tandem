package io.github.tendo505.tandem.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.github.tendo505.tandem.app.R
import io.github.tendo505.tandem.core.Item
import io.github.tendo505.tandem.core.Limits
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListScreen(vm: ListViewModel, back: () -> Unit) {
    val list by vm.list.collectAsStateWithLifecycle()
    val pending by vm.pending.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { vm.liveSync() } }

    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var renaming by rememberSaveable { mutableStateOf(false) }
    var leaving by rememberSaveable { mutableStateOf(false) }
    val title = list?.title?.ifBlank { null } ?: stringResource(R.string.untitled)
    val deletedMessage = stringResource(R.string.item_deleted)
    val undoLabel = stringResource(R.string.undo)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.clickable { renaming = true }) },
                navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
                actions = {
                    IconButton(onClick = { shareList(context, title, vm.listId) }) { Icon(Icons.Default.Share, stringResource(R.string.share)) }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.more)) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.copy_code)) }, onClick = { menu = false; copyCode(context, vm.listId) })
                            DropdownMenuItem(text = { Text(stringResource(R.string.rename)) }, onClick = { menu = false; renaming = true })
                            DropdownMenuItem(text = { Text(stringResource(R.string.clear_done)) }, onClick = { menu = false; vm.clearDone() })
                            DropdownMenuItem(text = { Text(stringResource(R.string.leave)) }, onClick = { menu = false; leaving = true })
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = { AddItemBar(onAdd = vm::add) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            SyncBanner(status, pending)
            val items = list?.items.orEmpty()
            val view = list
            if (view != null && items.isEmpty()) {
                val waitingForDownload = view.title.isEmpty() && pending == 0
                Text(
                    stringResource(if (waitingForDownload) R.string.downloading_list else R.string.empty_list),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
            }
            LazyColumn(Modifier.weight(1f).testTag("items")) {
                itemsIndexed(items, key = { _, item -> item.id }) { index, item ->
                    ItemRow(
                        item = item,
                        canMoveUp = index > 0,
                        canMoveDown = index < items.lastIndex,
                        onToggle = { vm.toggle(item) },
                        onEdit = { vm.setText(item, it) },
                        onMove = { vm.move(item, it) },
                        onDelete = {
                            vm.delete(item)
                            scope.launch {
                                val result = snackbar.showSnackbar(deletedMessage, undoLabel, duration = SnackbarDuration.Short)
                                if (result == SnackbarResult.ActionPerformed) vm.restore(item)
                            }
                        },
                        modifier = Modifier.animateItem(),
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainer)
                }
            }
        }
    }

    if (renaming) {
        TextInputDialog(
            title = stringResource(R.string.rename),
            label = stringResource(R.string.list_name),
            confirm = stringResource(R.string.save),
            initial = list?.title.orEmpty(),
            onDismiss = { renaming = false },
            onConfirm = { renaming = false; vm.rename(it) },
        )
    }
    if (leaving) {
        val body = stringResource(R.string.leave_confirm_body, vm.listId)
        AlertDialog(
            onDismissRequest = { leaving = false },
            title = { Text(stringResource(R.string.leave_confirm_title)) },
            text = { Text(if (pending > 0) stringResource(R.string.leave_confirm_pending, body) else body) },
            confirmButton = { TextButton(onClick = { leaving = false; vm.leave(back) }) { Text(stringResource(R.string.leave_confirm)) } },
            dismissButton = { TextButton(onClick = { leaving = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun ItemRow(
    item: Item,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onToggle: () -> Unit,
    onEdit: (String) -> Unit,
    onMove: (Int) -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var editing by rememberSaveable(item.id) { mutableStateOf(false) }
    var draft by rememberSaveable(item.id) { mutableStateOf(item.text) }
    var menu by remember { mutableStateOf(false) }
    val commit = { onEdit(draft); editing = false }

    Row(modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        val markDone = stringResource(R.string.mark_done, item.text)
        val editLabel = stringResource(R.string.edit_item)
        Checkbox(checked = item.done, onCheckedChange = { onToggle() }, modifier = Modifier.testTag("check-${item.text}").semantics { contentDescription = markDone })
        if (editing) {
            val focus = remember { FocusRequester() }
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it.take(Limits.MAX_TEXT) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { commit() }),
                modifier = Modifier.weight(1f).focusRequester(focus),
            )
            LaunchedEffect(Unit) { focus.requestFocus() }
            IconButton(onClick = commit) { Icon(Icons.Default.Check, stringResource(R.string.save)) }
        } else {
            Text(
                item.text,
                style = MaterialTheme.typography.bodyLarge.copy(textDecoration = if (item.done) TextDecoration.LineThrough else null),
                color = if (item.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClickLabel = editLabel) { draft = item.text; editing = true }
                    .padding(vertical = 14.dp),
            )
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.more)) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.move_up)) }, enabled = canMoveUp, onClick = { menu = false; onMove(-1) })
                DropdownMenuItem(text = { Text(stringResource(R.string.move_down)) }, enabled = canMoveDown, onClick = { menu = false; onMove(1) })
                DropdownMenuItem(text = { Text(stringResource(R.string.delete)) }, onClick = { menu = false; onDelete() })
            }
        }
    }
}

@Composable
private fun AddItemBar(onAdd: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val submit = { if (text.isNotBlank()) { onAdd(text); text = "" } }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 2.dp) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(Limits.MAX_TEXT) },
                placeholder = { Text(stringResource(R.string.add_item_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { submit() }),
                modifier = Modifier.weight(1f).testTag("add-input"),
            )
            IconButton(onClick = submit, enabled = text.isNotBlank()) {
                Icon(Icons.AutoMirrored.Filled.Send, stringResource(R.string.add), tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

private fun shareList(context: Context, title: String, code: String) {
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_TEXT, context.getString(R.string.share_text, title, code))
    context.startActivity(Intent.createChooser(send, context.getString(R.string.share_via)))
}

private fun copyCode(context: Context, code: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Tandem list code", code))
    Toast.makeText(context, R.string.code_copied, Toast.LENGTH_SHORT).show()
}
