package dev.turjo.paperless.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.turjo.paperless.core.model.Tag
import dev.turjo.paperless.core.model.TagSummary
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow

private val tagColors = listOf(
    Color(0xFF6185BD), Color(0xFF629E85), Color(0xFFBE795B), Color(0xFFA379AD),
    Color(0xFFB5944E), Color(0xFF5C9DA9), Color(0xFFBD758E), Color(0xFF858F55),
)

@Composable
internal fun TagDot(colorIndex: Int) {
    Box(Modifier.size(8.dp).background(tagColors[colorIndex], CircleShape))
}

@Composable
internal fun DocumentTagChips(tags: List<Tag>) {
    if (tags.isEmpty()) return
    Row(
        Modifier.padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (tag in tags.take(2)) {
            Surface(
                modifier = Modifier.weight(1f, fill = false),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Row(
                    Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    TagDot(tag.colorIndex)
                    Text(tag.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        if (tags.size > 2) {
            Text(stringResource(R.string.library_more_tags, tags.size - 2), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
internal fun EditTagsDialog(
    assigned: Flow<List<Tag>>,
    tags: List<TagSummary>,
    onDismiss: () -> Unit,
    onSave: (Set<String>, String?) -> Unit,
) {
    val current by assigned.collectAsStateWithLifecycle(initialValue = null)
    val initial = current ?: return
    var selected by rememberSaveable { mutableStateOf(ArrayList(initial.map(Tag::id))) }
    var name by rememberSaveable { mutableStateOf("") }
    val trimmed = name.trim()
    val duplicate = trimmed.isNotEmpty() && tags.any { it.tag.name.equals(trimmed, ignoreCase = true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.library_tags_title)) },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                for ((tag) in tags) {
                    val checked = tag.id in selected
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .toggleable(checked, role = Role.Checkbox) {
                                selected = ArrayList(if (it) selected + tag.id else selected - tag.id)
                            },
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked, onCheckedChange = null)
                        TagDot(tag.colorIndex)
                        Text(tag.name)
                    }
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.library_new_tag)) },
                    supportingText = if (duplicate) { { Text(stringResource(R.string.library_tag_exists)) } } else null,
                    isError = duplicate || trimmed.length > 30,
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !duplicate && trimmed.length <= 30,
                onClick = { onSave(selected.toSet(), trimmed.ifBlank { null }) },
            ) { Text(stringResource(R.string.detail_save)) }
        },
        dismissButton = { CancelButton(onDismiss) },
    )
}

@Composable
internal fun ManageTagsDialog(
    tags: List<TagSummary>,
    onDismiss: () -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
) {
    var renameId by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteId by rememberSaveable { mutableStateOf<String?>(null) }
    val rename = tags.firstOrNull { it.tag.id == renameId }?.tag
    val delete = tags.firstOrNull { it.tag.id == deleteId }?.tag
    when {
        rename != null -> {
            var name by rememberSaveable(rename.id) { mutableStateOf(rename.name) }
            val trimmed = name.trim()
            val duplicate = tags.any { it.tag.id != rename.id && it.tag.name.equals(trimmed, ignoreCase = true) }
            AlertDialog(
                onDismissRequest = { renameId = null },
                title = { Text(stringResource(R.string.library_rename_tag)) },
                text = {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(stringResource(R.string.library_tag_name)) },
                        singleLine = true,
                        supportingText = if (duplicate) { { Text(stringResource(R.string.library_tag_exists)) } } else null,
                        isError = duplicate || trimmed.length > 30,
                    )
                },
                confirmButton = {
                    TextButton(
                        enabled = !duplicate && trimmed.length in 1..30,
                        onClick = { onRename(rename.id, trimmed); renameId = null },
                    ) { Text(stringResource(R.string.detail_save)) }
                },
                dismissButton = { CancelButton { renameId = null } },
            )
        }
        delete != null -> AlertDialog(
            onDismissRequest = { deleteId = null },
            title = { Text(stringResource(R.string.library_delete_tag, delete.name)) },
            text = { Text(stringResource(R.string.library_delete_tag_message)) },
            confirmButton = {
                TextButton(onClick = { onDelete(delete.id); deleteId = null }) {
                    Text(stringResource(R.string.detail_delete))
                }
            },
            dismissButton = { CancelButton { deleteId = null } },
        )
        else -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.library_manage_tags)) },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    if (tags.isEmpty()) Text(stringResource(R.string.library_no_tags))
                    for ((tag, count) in tags) {
                        var menu by remember(tag.id) { mutableStateOf(false) }
                        Box {
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { menu = true },
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                TagDot(tag.colorIndex)
                                Text(tag.name, Modifier.weight(1f))
                                Text(stringResource(R.string.library_tag_count, count))
                            }
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.library_rename_tag)) },
                                    onClick = { menu = false; renameId = tag.id },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.detail_delete)) },
                                    onClick = { menu = false; deleteId = tag.id },
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.library_close)) }
            },
        )
    }
}

@Composable
internal fun SwipeDocumentCard(
    enabled: Boolean,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    content: @Composable () -> Unit,
) {
    if (!enabled) {
        content()
        return
    }
    val swipe = rememberSwipeToDismissBoxState()
    val scope = rememberCoroutineScope()
    SwipeToDismissBox(
        state = swipe,
        onDismiss = { direction ->
            when (direction) {
                SwipeToDismissBoxValue.StartToEnd -> onShare()
                SwipeToDismissBoxValue.EndToStart -> onDelete()
                SwipeToDismissBoxValue.Settled -> Unit
            }
            scope.launch { swipe.reset() }
        },
        backgroundContent = {
            val direction = swipe.dismissDirection
            if (direction != SwipeToDismissBoxValue.Settled) {
                val deleting = direction == SwipeToDismissBoxValue.EndToStart
                val colors = MaterialTheme.colorScheme
                Box(
                    Modifier.fillMaxSize()
                        .background(if (deleting) colors.error else colors.primaryContainer, CardDefaults.shape)
                        .padding(horizontal = 8.dp),
                    contentAlignment = if (deleting) Alignment.CenterEnd else Alignment.CenterStart,
                ) {
                    Text(
                        stringResource(if (deleting) R.string.detail_delete else R.string.detail_share),
                        color = if (deleting) colors.onError else colors.onPrimaryContainer,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        },
    ) { content() }
}
