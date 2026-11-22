package dev.turjo.paperless.feature.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import dev.turjo.paperless.core.model.TabState
import kotlinx.coroutines.launch
import kotlin.math.abs

internal data class OutlineDraft(val original: List<OutlineRow>, val rows: List<OutlineRow> = original) {
    val dirty get() = rows != original
}

private data class HeadingChoice(val row: OutlineRow, val checked: Boolean = true)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReaderOutlineSheet(
    tab: TabState,
    draft: OutlineDraft?,
    onDraft: (OutlineDraft?) -> Unit,
    viewModel: ReaderViewModel,
    jump: (Int) -> Unit,
    dismiss: () -> Unit,
) {
    var loading by remember { mutableStateOf(draft == null) }
    var editing by remember { mutableStateOf(draft?.dirty == true) }
    var collapsed by remember { mutableStateOf(emptySet<Int>()) }
    var rename by remember { mutableStateOf<Int?>(null) }
    var choices by remember { mutableStateOf<List<HeadingChoice>?>(null) }
    var saveOptions by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val rows = draft?.rows.orEmpty()
    LaunchedEffect(tab.pdfKey) {
        if (draft == null) {
            val outline = viewModel.readOutline(tab.pdfKey)
            if (outline == null) message = "Could not read bookmarks."
            else onDraft(OutlineDraft(outline.flattenOutline()))
            loading = false
        }
    }
    fun edit(updated: List<OutlineRow>) {
        if (draft != null && updated != rows) {
            collapsed = emptySet()
            onDraft(draft.copy(rows = updated))
        }
    }
    fun save(overwrite: Boolean) {
        saveOptions = false
        busy = true
        scope.launch {
            if (viewModel.saveOutline(tab.tabId, rows.toOutline(), overwrite)) {
                onDraft(null)
                dismiss()
            } else {
                message = if (overwrite) "Could not overwrite this PDF. Try saving a copy." else "Could not save a copy."
            }
            busy = false
        }
    }
    ModalBottomSheet(onDismissRequest = { if (!busy) dismiss() }) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Bookmarks", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                if (draft != null) TextButton({ editing = !editing }, enabled = !busy) {
                    Text(if (editing) "View" else "Edit")
                }
                if (draft?.dirty == true) TextButton({
                    if (tab.pdfKey.value.startsWith("doc:")) save(false) else saveOptions = true
                }, enabled = !busy) { Text("Save") }
            }
            if (loading || busy) CircularProgressIndicator()
            message?.let { Text(it, Modifier.padding(vertical = 8.dp)) }
            if (editing && draft != null) {
                Row {
                    TextButton({ edit(rows + OutlineRow("Page ${tab.page + 1}", tab.page, 0)) }, enabled = !busy) {
                        Text("+ This page")
                    }
                    TextButton({
                        busy = true
                        message = null
                        scope.launch {
                            val suggestions = viewModel.suggestOutline(tab.pdfKey)
                            when {
                                suggestions == null -> message = "Could not read headings."
                                suggestions.isEmpty() -> message = "No headings found. Scanned PDFs need a text layer."
                                else -> choices = suggestions.flattenOutline().map { HeadingChoice(it) }
                            }
                            busy = false
                        }
                    }, enabled = !busy) { Text("Suggest from headings") }
                }
            }
            if (draft != null && rows.isEmpty()) Text("No bookmarks", Modifier.padding(12.dp))
            val current = rows.indices.filter { rows[it].page <= tab.page }
                .maxWithOrNull(compareBy({ rows[it].page }, { rows[it].level }, { it }))
            val visibleRows = buildList {
                var index = 0
                while (index < rows.size) {
                    add(index)
                    index = if (!editing && index in collapsed) rows.branchEnd(index) else index + 1
                }
            }
            val highlighted = current?.let { chapter ->
                visibleRows.lastOrNull { it <= chapter && chapter < rows.branchEnd(it) }
            }
            LazyColumn(Modifier.weight(1f, fill = false)) {
                items(visibleRows.size) { item ->
                    val index = visibleRows[item]
                    val row = rows[index]
                    val hasChildren = rows.getOrNull(index + 1)?.level == row.level + 1
                    Column(Modifier.fillMaxWidth().background(
                        if (index == highlighted) MaterialTheme.colorScheme.secondaryContainer
                        else MaterialTheme.colorScheme.surfaceContainerLow,
                    )) {
                        Row(Modifier.padding(start = (12 * row.level.coerceAtMost(10)).dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            if (hasChildren && !editing) ReaderIcon(
                                if (index in collapsed) "▸" else "▾", "Toggle chapter",
                            ) {
                                collapsed = if (index in collapsed) collapsed - index else collapsed + index
                            }
                            Text("${row.title} · ${row.page + 1}",
                                Modifier.weight(1f).clickable(enabled = !busy) { jump(row.page) }.padding(10.dp))
                            if (editing && !busy) {
                                val threshold = with(LocalDensity.current) { 32.dp.toPx() }
                                Text("☰", Modifier.pointerInput(rows, index) {
                                    var movement = Offset.Zero
                                    detectDragGestures(onDragStart = { movement = Offset.Zero }, onDragEnd = {
                                        val horizontal = abs(movement.x) > abs(movement.y)
                                        val distance = if (horizontal) movement.x else movement.y
                                        if (abs(distance) >= threshold) {
                                            val direction = if (distance > 0f) 1 else -1
                                            edit(if (horizontal) rows.changeLevel(index, direction) else rows.moveBranch(index, direction))
                                        }
                                    }) { change, amount ->
                                        change.consume()
                                        movement += amount
                                    }
                                }.padding(12.dp))
                            }
                        }
                        if (editing) Row {
                            TextButton({ rename = index }, enabled = !busy) { Text("Rename") }
                            TextButton({ edit(rows.filterIndexed { i, _ -> i !in index until rows.branchEnd(index) }) },
                                enabled = !busy) { Text("Delete") }
                            ReaderIcon("←", "Outdent") { if (!busy) edit(rows.changeLevel(index, -1)) }
                            ReaderIcon("→", "Indent") { if (!busy) edit(rows.changeLevel(index, 1)) }
                        }
                    }
                }
            }
        }
    }
    rename?.let { index ->
        var name by remember(index) { mutableStateOf(rows[index].title) }
        AlertDialog(
            onDismissRequest = { rename = null },
            title = { Text("Rename bookmark") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true) },
            confirmButton = {
                TextButton({
                    edit(rows.mapIndexed { i, row -> if (i == index) row.copy(title = name.trim()) else row })
                    rename = null
                }, enabled = name.isNotBlank()) { Text("Rename") }
            },
            dismissButton = { TextButton({ rename = null }) { Text("Cancel") } },
        )
    }
    choices?.let { headings ->
        AlertDialog(
            onDismissRequest = { choices = null },
            title = { Text("Review suggested headings") },
            text = {
                LazyColumn {
                    items(headings.size) { index ->
                        val heading = headings[index]
                        fun update(choice: HeadingChoice) {
                            choices = headings.mapIndexed { i, old -> if (i == index) choice else old }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(heading.checked, { update(heading.copy(checked = it)) })
                            Text("${heading.row.title} · p. ${heading.row.page + 1}", Modifier.weight(1f))
                        }
                        Row(Modifier.padding(start = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                            ReaderIcon("−", "Decrease level") {
                                update(heading.copy(row = heading.row.copy(level = (heading.row.level - 1).coerceAtLeast(0))))
                            }
                            Text("Level ${heading.row.level + 1}")
                            ReaderIcon("+", "Increase level") {
                                val max = headings.getOrNull(index - 1)?.row?.level?.plus(1) ?: 0
                                update(heading.copy(row = heading.row.copy(level = (heading.row.level + 1).coerceAtMost(max))))
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton({
                    edit(rows.mergeSuggestions(headings.filter { it.checked }.map { it.row }))
                    choices = null
                }, enabled = headings.any { it.checked }) { Text("Add selected") }
            },
            dismissButton = { TextButton({ choices = null }) { Text("Cancel") } },
        )
    }
    if (saveOptions) AlertDialog(
        onDismissRequest = { saveOptions = false },
        title = { Text("Save bookmarks") },
        text = { TextButton({ save(false) }) { Text("Save as copy") } },
        confirmButton = { TextButton({ save(true) }) { Text("Save (overwrite)") } },
        dismissButton = { TextButton({ saveOptions = false }) { Text("Cancel") } },
    )
}
