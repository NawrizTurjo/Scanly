package dev.turjo.paperless.feature.tools

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.WindowInsets
import dev.turjo.paperless.core.designsystem.ScanlyScaffold
import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import dev.turjo.paperless.core.designsystem.AlertDialog
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.turjo.paperless.core.pdf.ShareIntents
import dev.turjo.paperless.core.pdf.tools.*
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlin.math.roundToInt

private val batchTools = listOf(Tool.COMPRESS, Tool.WIDTH, Tool.GRAYSCALE, Tool.NUMBERS, Tool.SANITIZE, Tool.PASSWORD, Tool.MERGE)
private val pdf = arrayOf("application/pdf")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsScreen(
    onOpenPdf: (File) -> Unit = {},
    onAdjustImages: ((List<Uri>) -> Unit)? = null,
    initialTool: String? = null,
    onClose: (() -> Unit)? = null,
) {
    val model = toolsModel(initialTool = initialTool)
    val state by model.state.collectAsStateWithLifecycle()
    val tool = state.tool
    BackHandler(tool != null, model::back)
    ScanlyScaffold(
        Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(tool?.title ?: R.string.tools_title)) },
                navigationIcon = {
                    val back = if (tool != null) model::back else onClose
                    if (back != null) TextButton(onClick = back) { Text(stringResource(R.string.tools_back)) }
                },
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            if (tool == null) {
                ToolsLanding(model, onOpenPdf)
            } else {
                ToolFlow(tool, state, model, onOpenPdf, onAdjustImages)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ToolFlow(
    tool: Tool,
    state: ToolsState,
    model: ToolsModel,
    open: (File) -> Unit,
    onAdjustImages: ((List<Uri>) -> Unit)?,
) {
    val context = LocalContext.current
    var batchTool by rememberSaveable { mutableStateOf(Tool.COMPRESS) }
    val operation = if (tool == Tool.BATCH) batchTool else tool
    val single = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { model.pick(listOf(it)) }
    }
    val multiple = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments(), model::pick)
    val photo = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(), model::pick)
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { model.pickFolder(it) }
    }
    var insertAt by rememberSaveable { mutableIntStateOf(-1) }
    var insertRange by rememberSaveable { mutableStateOf("") }
    val insert = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { model.insert(it, insertAt, insertRange) }
        insertAt = -1
    }
    val notes = state.details.firstOrNull()?.metadata
    val options = remember(state.inputs, operation, notes) {
        mutableStateMapOf<String, String>().apply {
            notes?.let {
                put("Title", it.title.orEmpty())
                put("Author", it.author.orEmpty())
                put("Subject", it.subject.orEmpty())
                put("Keywords", it.keywords.orEmpty())
            }
        }
    }
    val selection = remember(state.inputs, operation) { mutableStateListOf<Int>() }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        model.startBatch(operation, options.toMap())
        options.remove("Password")
    }
    val share: (List<Output>) -> Unit = { outputs ->
        model.shareable(outputs) { files ->
            val mimes = files.map { it.imageMime() ?: "application/pdf" }.distinct()
            context.startActivity(ShareIntents.forFiles(context, files, mimes.singleOrNull() ?: "*/*"))
        }
    }

    if (insertAt >= 0) {
        AlertDialog(
            onDismissRequest = { insertAt = -1 },
            title = { Text(stringResource(R.string.tools_insert_pages)) },
            text = {
                OutlinedTextField(insertRange, { insertRange = it },
                    label = { Text(stringResource(R.string.tools_insert_ranges)) }, singleLine = true)
            },
            confirmButton = { TextButton(onClick = { insert.launch(pdf) }) { Text(stringResource(R.string.tools_choose_pdf)) } },
            dismissButton = { TextButton(onClick = {
                model.run(emptyMap(), listOf(PageOp.InsertBlank(insertAt)))
                insertAt = -1
            }) { Text(stringResource(R.string.tools_insert_blank)) } },
        )
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        state.error?.let { error -> item { Text(error, color = MaterialTheme.colorScheme.error) } }
        state.message?.let { message -> item { Text(message) } }
        when {
            state.busy -> item {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                state.progress?.let { Text(it) }
                TextButton(onClick = model::back) { Text(stringResource(R.string.tools_cancel)) }
            }
            state.batchId != null -> {
                if (state.batchRunning) item {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    val count = state.batchItems.size
                    Text(pluralStringResource(R.plurals.tools_files_processed, count, count))
                    TextButton(onClick = model::stopBatch, enabled = !state.stopping) {
                        Text(if (state.stopping) "Stopping after current file" else "Cancel")
                    }
                }
                items(state.batchItems) { entry ->
                    val status = when (entry.status) {
                        BatchStatus.DONE -> "✓ Done"
                        BatchStatus.SKIPPED -> "– Skipped"
                        BatchStatus.FAILED -> "✗ Failed"
                    }
                    Text("${entry.name} · $status")
                    entry.reason?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    entry.outputs.forEach { ResultFile(it, model, share, open) }
                }
            }
            state.outputs.isNotEmpty() -> {
                items(state.outputs) {
                    if (tool == Tool.COMPRESS) {
                        val before = state.inputBytes
                        if (before != null) {
                            val from = android.text.format.Formatter.formatFileSize(context, before)
                            val to = android.text.format.Formatter.formatFileSize(context, it.bytes)
                            Text("$from → $to")
                        }
                    }
                    ResultFile(it, model, share, open)
                }
                if (state.outputs.size > 1) item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { share(state.outputs) }) { Text(stringResource(R.string.tools_share_all)) }
                        OutlinedButton(onClick = { model.save(state.outputs) }) { Text(stringResource(R.string.tools_save_all)) }
                    }
                }
            }
            state.inputs.isEmpty() -> item {
                Button(onClick = {
                    when (tool) {
                        Tool.FROM_IMAGES -> photo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        Tool.MERGE, Tool.WIDTH, Tool.BATCH -> multiple.launch(pdf)
                        else -> single.launch(pdf)
                    }
                }) {
                    Text(when (tool) {
                        Tool.FROM_IMAGES -> "Choose photos"
                        Tool.MERGE, Tool.WIDTH, Tool.BATCH -> "Choose PDFs"
                        else -> "Choose PDF"
                    })
                }
                if (tool == Tool.BATCH) {
                    OutlinedButton(onClick = { folder.launch(null) }) { Text(stringResource(R.string.tools_choose_folder)) }
                    TextButton(onClick = model::latestBatch) { Text(stringResource(R.string.tools_latest_batch)) }
                }
            }
            state.locked != null -> item {
                var password by remember(state.locked) { mutableStateOf("") }
                Text("${state.inputs[checkNotNull(state.locked)].name} is password-protected")
                OutlinedTextField(password, { password = it }, label = { Text(stringResource(R.string.tools_password)) },
                    visualTransformation = PasswordVisualTransformation(), singleLine = true)
                Button(onClick = { model.unlock(password) }) {
                    Text(if (tool == Tool.PASSWORD) "Remove password" else "Unlock")
                }
            }
            else -> {
                items(state.inputs.size) { index ->
                    val input = state.inputs[index]
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(input.name)
                            if (tool == Tool.WIDTH) {
                                val widths = state.details.getOrNull(index)?.widths.orEmpty()
                                val summaries = widths.groupingBy { it.roundToInt() }.eachCount().entries
                                    .sortedByDescending { it.value }.map { (width, count) ->
                                        pluralStringResource(R.plurals.tools_pages_at_width, count, count, width)
                                    }
                                Text(summaries.joinToString(", "))
                            }
                        }
                        if (tool == Tool.MERGE || tool == Tool.FROM_IMAGES) {
                            TextButton(onClick = { model.move(index, -1) }, enabled = index > 0) { Text(stringResource(R.string.tools_up)) }
                            TextButton(onClick = { model.move(index, 1) }, enabled = index < state.inputs.lastIndex) { Text(stringResource(R.string.tools_down)) }
                        }
                    }
                }
                if (tool == Tool.BATCH) item {
                    val names = batchTools.associateWith { stringResource(it.title) }
                    Choice("Operation", batchTool, batchTools, { names.getValue(it) }) { batchTool = it }
                    if (batchTool == Tool.MERGE) Text(stringResource(R.string.tools_merge_order))
                }
                val pageCount = state.details.firstOrNull()?.widths?.size ?: 0
                item { ToolOptions(operation, options, pageCount, tool == Tool.BATCH) }
                val pickPages = tool == Tool.SPLIT && options["Split by"] == "Pick pages"
                if (tool == Tool.PAGES || pickPages) {
                    val input = state.inputs.first().file
                    if (tool == Tool.PAGES) item {
                        PageEditActions(selection.toList(), pageCount, model)
                        TextButton(onClick = { insertRange = ""; insertAt = 0 }) { Text(stringResource(R.string.tools_insert_before_first)) }
                    }
                    items((pageCount + 2) / 3) { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (index in row * 3 until minOf(pageCount, row * 3 + 3)) {
                                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                    PageTile(input, index, index in selection) {
                                        if (!selection.remove(index)) selection.add(index)
                                    }
                                    if (tool == Tool.PAGES) TextButton(onClick = { insertRange = ""; insertAt = index + 1 }) {
                                        Text(stringResource(R.string.tools_insert))
                                    }
                                }
                            }
                            repeat((3 - (pageCount - row * 3)).coerceAtLeast(0)) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
                if (tool != Tool.PAGES) item {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            enabled = (!pickPages || selection.isNotEmpty()) &&
                                (operation != Tool.MERGE || state.inputs.size >= 2) &&
                                (tool == Tool.BATCH || tool == Tool.FROM_IMAGES || state.details.size == state.inputs.size),
                            onClick = {
                                val chosen = options.toMap()
                                if (tool == Tool.BATCH) {
                                    if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(
                                            Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                                        permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    } else {
                                        model.startBatch(operation, chosen)
                                        options.remove("Password")
                                    }
                                } else {
                                    model.run(if (pickPages) chosen + ("Pages" to selection.sorted().joinToString { "${it + 1}" }) else chosen)
                                    options.remove("Password")
                                }
                            },
                        ) { Text(if (tool == Tool.BATCH) "Start batch" else "Run") }
                        if (tool == Tool.FROM_IMAGES && onAdjustImages != null) {
                            OutlinedButton(onClick = {
                                onAdjustImages(state.inputs.map { it.photoUri ?: Uri.fromFile(it.file) })
                            }) { Text(stringResource(R.string.tools_adjust_images)) }
                        }
                    }
                    if (tool == Tool.FROM_IMAGES && onAdjustImages != null) {
                        Text(stringResource(R.string.tools_adjust_images_hint), style = MaterialTheme.typography.bodySmall)
                    }
                    if (tool == Tool.PRINT) OutlinedButton(onClick = {
                        model.run(options.toMap() + ("Test sheet" to "true"))
                    }) { Text(stringResource(R.string.tools_print_test)) }
                }
            }
        }
    }
}

@Composable
private fun ResultFile(output: Output, model: ToolsModel, share: (List<Output>) -> Unit, open: (File) -> Unit) {
    val context = LocalContext.current
    val image = output.file.imageMime() != null
    Column {
        Text(output.file.name, style = MaterialTheme.typography.titleMedium)
        Text(android.text.format.Formatter.formatFileSize(context, output.bytes))
        output.note?.let { Text(it) }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { share(listOf(output)) }) { Text(stringResource(R.string.tools_share)) }
            TextButton(onClick = { model.save(listOf(output)) }) { Text(stringResource(R.string.tools_save)) }
            if (!image) TextButton(onClick = { model.openOutput(output, open) }) { Text(stringResource(R.string.tools_open_reader)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PageTile(file: File, index: Int, selected: Boolean, click: () -> Unit) {
    val bitmap by produceState<Bitmap?>(null, file, index) {
        try {
            value = thumbnail(file, index)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            value = null
        }
    }
    FilterChip(selected, click, label = {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            bitmap?.let { Image(it.asImageBitmap(), null, Modifier.height(96.dp)) }
            Text("Page ${index + 1}")
        }
    })
}

@Composable
private fun PageEditActions(selected: List<Int>, count: Int, model: ToolsModel) {
    var destination by rememberSaveable { mutableStateOf("") }
    val at = destination.toIntOrNull()?.minus(1)
    val pages = selected.toSet()
    Column {
        Row {
            TextButton(onClick = { model.run(emptyMap(), listOf(PageOp.Delete(pages))) },
                enabled = pages.isNotEmpty() && pages.size < count) { Text(stringResource(R.string.tools_delete)) }
            TextButton(onClick = { model.run(emptyMap(), listOf(PageOp.Extract(selected.sorted()))) },
                enabled = pages.isNotEmpty()) { Text(stringResource(R.string.tools_extract)) }
            TextButton(onClick = { model.run(emptyMap(), selected.sortedDescending().map { PageOp.Duplicate(it) }) },
                enabled = pages.isNotEmpty()) { Text(stringResource(R.string.tools_duplicate)) }
        }
        Row {
            TextButton(onClick = { model.run(emptyMap(), listOf(PageOp.Rotate(pages, -90))) },
                enabled = pages.isNotEmpty()) { Text(stringResource(R.string.tools_rotate_left)) }
            TextButton(onClick = { model.run(emptyMap(), listOf(PageOp.Rotate(pages, 90))) },
                enabled = pages.isNotEmpty()) { Text(stringResource(R.string.tools_rotate_right)) }
        }
        if (selected.size == 1 && count > 1) {
            OutlinedTextField(destination, { destination = it }, singleLine = true, label = { Text(stringResource(R.string.tools_page_position)) })
            TextButton(enabled = at != null && at in 0 until count, onClick = {
                val order = (0 until count).toMutableList()
                order.add(checkNotNull(at), order.removeAt(selected.single()))
                model.run(emptyMap(), listOf(PageOp.Reorder(order)))
            }) { Text(stringResource(R.string.tools_move_page)) }
        }
    }
}

@Composable
internal fun toolsModel(key: String? = null, initialTool: String? = null): ToolsModel {
    val application = LocalContext.current.applicationContext as Application
    return viewModel(key = key, factory = viewModelFactory {
        initializer {
            ToolsModel(application).apply {
                Tool.entries.firstOrNull { it.name == initialTool }?.let(::select)
            }
        }
    })
}
