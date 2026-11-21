package dev.turjo.paperless.feature.library

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.turjo.paperless.core.model.DeletedDocument
import dev.turjo.paperless.core.model.FolderSummary
import java.util.concurrent.TimeUnit

@Composable
internal fun FolderCard(
    summary: FolderSummary,
    onOpen: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    var rename by rememberSaveable { mutableStateOf(false) }
    var delete by rememberSaveable { mutableStateOf(false) }
    val actions = stringResource(R.string.library_folder_actions)
    Box {
        Card(Modifier.fillMaxWidth().combinedClickable(
            onClick = onOpen, onLongClick = { menu = true }, onLongClickLabel = actions,
        )) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(summary.folder.name, style = MaterialTheme.typography.titleMedium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(pluralStringResource(
                    R.plurals.library_document_count, summary.documentCount, summary.documentCount,
                ))
            }
        }
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.detail_rename)) },
                onClick = { menu = false; rename = true },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.detail_delete)) },
                onClick = { menu = false; delete = true },
            )
        }
    }
    if (rename) FolderNameDialog(summary.folder.name, { rename = false }) {
        rename = false
        onRename(it)
    }
    if (delete) LibraryConfirmation(
        R.string.library_delete_folder, R.string.library_delete_folder_message, R.string.detail_delete,
        onDismiss = { delete = false }, onConfirm = { delete = false; onDelete() },
    )
}

@Composable
internal fun FolderNameDialog(
    initialName: String = "",
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var name by rememberSaveable(initialName) { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(
            if (initialName.isEmpty()) R.string.library_new_folder else R.string.detail_rename,
        )) },
        text = {
            OutlinedTextField(
                value = name, onValueChange = { name = it }, singleLine = true,
                label = { Text(stringResource(R.string.library_folder_name)) },
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(name.trim()) }, enabled = name.isNotBlank()) {
                Text(stringResource(R.string.library_save))
            }
        },
        dismissButton = { CancelButton(onDismiss) },
    )
}

@Composable
internal fun MoveToFolderDialog(
    folders: List<FolderSummary>,
    onDismiss: () -> Unit,
    onSelect: (String?) -> Unit,
    onCreate: (String) -> Unit,
) {
    var creating by rememberSaveable { mutableStateOf(false) }
    if (creating) {
        FolderNameDialog(onDismiss = { creating = false }, onSave = onCreate)
        return
    }
    val children = remember(folders) { folders.groupBy { it.folder.parentId } }
    val ordered = children[null].orEmpty().flatMap { root ->
        listOf(root) + children[root.folder.id].orEmpty()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.library_move_to_folder)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                item {
                    TextButton(onClick = { onSelect(null) }) { Text(stringResource(R.string.library_root)) }
                }
                items(ordered, key = { it.folder.id }) { summary ->
                    val indent = if (summary.folder.parentId == null) 0.dp else 16.dp
                    TextButton(
                        modifier = Modifier.padding(start = indent),
                        onClick = { onSelect(summary.folder.id) },
                    ) { Text(summary.folder.name) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { creating = true }) { Text(stringResource(R.string.library_new_folder_more)) }
        },
        dismissButton = { CancelButton(onDismiss) },
    )
}

@Composable
internal fun RecycleBin(
    modifier: Modifier,
    deleted: List<DeletedDocument>,
    onRestore: (String) -> Unit,
    onDelete: (List<String>) -> Unit,
) {
    var empty by rememberSaveable { mutableStateOf(false) }
    var deleteId by rememberSaveable { mutableStateOf<String?>(null) }
    if (deleted.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.library_bin_empty))
        }
    } else {
        LazyColumn(modifier, contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                TextButton(onClick = { empty = true }, modifier = Modifier.padding(horizontal = 12.dp)) {
                    Text(stringResource(R.string.library_empty_bin))
                }
            }
            items(deleted, key = DeletedDocument::id) { document ->
                val age = TimeUnit.MILLISECONDS.toDays(
                    (System.currentTimeMillis() - document.deletedAt).coerceAtLeast(0L),
                ).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                val remaining = (RECYCLE_BIN_DAYS - age).coerceAtLeast(0)
                ListItem(
                    headlineContent = { Text(document.title) },
                    supportingContent = {
                        Column {
                            val deletedAgo = pluralStringResource(R.plurals.library_deleted_ago, age, age)
                            val removedIn = pluralStringResource(R.plurals.library_removed_in, remaining, remaining)
                            Text(stringResource(R.string.library_bin_age, deletedAgo, removedIn))
                            Row {
                                TextButton(onClick = { onRestore(document.id) }) {
                                    Text(stringResource(R.string.library_restore))
                                }
                                TextButton(onClick = { deleteId = document.id }) {
                                    Text(stringResource(R.string.library_delete_forever))
                                }
                            }
                        }
                    },
                )
            }
        }
    }
    if (empty || deleteId != null) {
        val action = if (empty) R.string.library_empty_bin else R.string.library_delete_forever
        LibraryConfirmation(
            action,
            if (empty) R.string.library_empty_bin_message else R.string.library_delete_forever_message,
            action,
            onDismiss = { empty = false; deleteId = null },
            onConfirm = {
                val ids = if (empty) deleted.map(DeletedDocument::id) else listOfNotNull(deleteId)
                empty = false
                deleteId = null
                onDelete(ids)
            },
        )
    }
}

@Composable
private fun LibraryConfirmation(
    title: Int,
    message: Int,
    action: Int,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = { Text(stringResource(message)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(action)) } },
        dismissButton = { CancelButton(onDismiss) },
    )
}
