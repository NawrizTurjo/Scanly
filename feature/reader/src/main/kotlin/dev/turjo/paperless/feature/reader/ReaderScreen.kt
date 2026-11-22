package dev.turjo.paperless.feature.reader

import android.provider.OpenableColumns
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import dev.turjo.paperless.core.pdf.PdfDocumentHandle
import kotlinx.coroutines.delay
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.turjo.paperless.core.model.PdfKey
import dev.turjo.paperless.core.model.ReaderViewMode
import dev.turjo.paperless.core.model.RecentPdf
import dev.turjo.paperless.core.model.TabState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    openRequest: PdfKey? = null,
    onOpenRequestConsumed: () -> Unit = {},
    openRequestTitle: String? = null,
) {
    val viewModel: ReaderViewModel = hiltViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val consumed by rememberUpdatedState(onOpenRequestConsumed)
    LaunchedEffect(openRequest) {
        if (openRequest != null) {
            viewModel.open(openRequest, title = openRequestTitle)
            consumed()
        }
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val defaultTitle = stringResource(R.string.reader_pdf)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val title = withContext(Dispatchers.IO) {
                try {
                    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                        ?.use { if (it.moveToFirst()) it.getString(0) else null }
                } catch (_: RuntimeException) {
                    null
                }
            }
            viewModel.openExternal(uri, title?.takeIf { it.isNotBlank() } ?: defaultTitle)
        }
    }
    val pickPdf: () -> Unit = { picker.launch(arrayOf("application/pdf")) }
    val snackbar = remember { SnackbarHostState() }
    val notice = state.notice?.let { stringResource(it.messageResource) }
    LaunchedEffect(notice) {
        if (notice != null) {
            snackbar.showSnackbar(notice)
            viewModel.dismissNotice()
        }
    }

    val tab = state.activeTab
    val document = state.document
    var showThumbnails by remember(tab?.tabId) { mutableStateOf(false) }

    val searches = viewModel.searches
    val crops = viewModel.crops
    val drafts = viewModel.drafts
    var searchMode by rememberSaveable { mutableStateOf(false) }
    var showOutline by remember(tab?.tabId) { mutableStateOf(false) }
    var jump by remember(tab?.tabId, document) { mutableStateOf<PageJump?>(null) }
    var closePending by remember { mutableStateOf<String?>(null) }
    var splitRequested by rememberSaveable { mutableStateOf(false) }
    var rightId by rememberSaveable { mutableStateOf<String?>(null) }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val split = landscape && splitRequested
    val search = tab?.let { searches[it.tabId] } ?: SearchState()
    val crop = tab?.let { crops[it.tabId] } ?: CropMode.OFF
    LaunchedEffect(tab?.tabId, document, search.query, searchMode) {
        if (tab == null || document == null || !searchMode || search.query.isBlank() || search.status != SearchStatus.PENDING) {
            return@LaunchedEffect
        }
        delay(250)
        val result = readerResult {
            val matches = document.search(search.query)
            val status = if (matches.isNotEmpty()) null else {
                val hasText = (0 until document.pageCount).any { document.pageText(it).isNotBlank() }
                if (hasText) SearchStatus.NO_MATCHES else SearchStatus.NO_TEXT
            }
            SearchState(search.query, matches, status = status)
        } ?: SearchState(search.query, status = SearchStatus.ERROR)
        searches[tab.tabId] = result
        result.current?.let { jump = PageJump(it.page, it) }
    }
    BackHandler(enabled = searchMode && tab != null && !showOutline) { searchMode = false }
    closePending?.let { id ->
        AlertDialog(
            onDismissRequest = { closePending = null },
            title = { Text("Discard unsaved bookmarks?") },
            confirmButton = { TextButton({ closePending = null; viewModel.close(id) }) { Text("Discard and close") } },
            dismissButton = { TextButton({ closePending = null }) { Text("Keep editing") } },
        )
    }
    if (tab != null && showOutline) {
        ReaderOutlineSheet(tab, drafts[tab.tabId],
            { if (it == null) drafts.remove(tab.tabId) else drafts[tab.tabId] = it },
            viewModel, { jump = PageJump(it); showOutline = false }, { showOutline = false })
    }

    Scaffold(
        topBar = {
            if (searchMode && tab != null) {
                Column(Modifier.statusBarsPadding()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        ReaderIcon("×", "Close search") { searchMode = false }
                        OutlinedTextField(search.query, {
                            searches[tab.tabId] = SearchState(it, status = if (it.isBlank()) null else SearchStatus.PENDING)
                        }, Modifier.weight(1f), singleLine = true, placeholder = { Text("Find in PDF") })
                        if (search.matches.isNotEmpty()) Text("${search.index + 1} / ${search.matches.size}")
                        for ((symbol, delta) in listOf("↑" to -1, "↓" to 1)) {
                            ReaderIcon(symbol, if (delta < 0) "Previous match" else "Next match",
                                enabled = search.current != null) {
                                val next = search.step(delta)
                                searches[tab.tabId] = next
                                next.current?.let { jump = PageJump(it.page, it) }
                            }
                        }
                    }
                    search.status?.let { Text(it.label, Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
                }
            } else {
                TopAppBar(
                    title = { Text(stringResource(R.string.reader_title)) },
                    actions = {
                        if (tab != null && document != null) {
                            ReaderIcon("⌕", "Search") { searchMode = true }
                            ReaderIcon("☰", "Bookmarks") { showOutline = true }
                            ReaderMenu(
                                tab, crop, landscape, split,
                                onViewMode = { viewModel.toggleViewMode(tab.tabId) },
                                onNight = { viewModel.toggleNightMode(tab.tabId) },
                                onThumbnails = { showThumbnails = true },
                                onCrop = { crops[tab.tabId] = it },
                                onSplit = { splitRequested = !splitRequested },
                            )
                        }
                    },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (state.tabs.isNotEmpty()) {
                TabStrip(state.tabs, state.activeTabId, viewModel::switch, { id ->
                    if (drafts[id]?.dirty == true) closePending = id else viewModel.close(id)
                }, pickPdf)
            }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                val openError = state.openError
                when {
                    state.restoring -> CircularProgressIndicator()
                    tab == null -> EmptyReader(state.recent, pickPdf, viewModel::openRecent)
                    openError != null -> Text(
                        stringResource(openError.messageResource),
                        Modifier.padding(24.dp),
                    )
                    document == null -> CircularProgressIndicator()
                    else -> {
                        val primary: @Composable () -> Unit = {
                            key(tab.tabId, document) {
                                ReaderPages(
                                    document = document,
                                    tab = tab,
                                    horizontalOffset = viewModel.horizontalOffset(tab.tabId),
                                    search = if (searchMode) search else SearchState(),
                                    requestedJump = jump,
                                    onJumpConsumed = { jump = null },
                                    crop = crop,
                                    showThumbnails = showThumbnails,
                                    onDismissThumbnails = { showThumbnails = false },
                                    onPosition = { page, offset -> viewModel.updatePosition(tab.tabId, page, offset) },
                                    onZoom = { zoom, offset -> viewModel.updateZoom(tab.tabId, zoom, offset) },
                                )
                            }
                        }
                        if (split) {
                            val right = state.tabs.firstOrNull { it.tabId == rightId }
                                ?: state.tabs.firstOrNull { it.tabId != tab.tabId } ?: tab
                            SplitReader(state.tabs, tab, right, document, viewModel, crops[right.tabId] ?: CropMode.OFF,
                                { rightId = it }, primary)
                        } else primary()
                    }
                }
            }
        }
    }
}

private val ReaderError.messageResource: Int
    get() = when (this) {
        ReaderError.ENCRYPTED -> R.string.reader_encrypted
        ReaderError.OPEN -> R.string.reader_open_error
        ReaderError.SAVE -> R.string.reader_save_error
    }

@Composable
private fun TabStrip(
    tabs: List<TabState>,
    activeId: String?,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
    onAdd: () -> Unit,
) {
    val list = rememberLazyListState()
    val activeIndex = tabs.indexOfFirst { it.tabId == activeId }
    LaunchedEffect(activeId, activeIndex) {
        if (activeIndex >= 0 && list.layoutInfo.visibleItemsInfo.none {
            it.index == activeIndex && it.offset >= 0 && it.offset + it.size <= list.layoutInfo.viewportEndOffset
        }) {
            list.scrollToItem(activeIndex)
        }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        LazyRow(
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            state = list,
        ) {
            items(tabs, key = { it.tabId }) { tab ->
                val active = tab.tabId == activeId
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = if (active) MaterialTheme.colorScheme.secondaryContainer
                    else MaterialTheme.colorScheme.surfaceContainer,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            onClick = { onSelect(tab.tabId) },
                            modifier = Modifier.semantics { selected = active },
                        ) {
                            Text(tab.title, Modifier.widthIn(max = 112.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        ReaderIcon("×", stringResource(R.string.reader_close_tab, tab.title)) { onClose(tab.tabId) }
                    }
                }
            }
        }
        ReaderIcon("+", stringResource(R.string.open_pdf), onClick = onAdd)
        if (tabs.size > 5) {
            var expanded by remember { mutableStateOf(false) }
            Box {
                ReaderIcon("▾", stringResource(R.string.reader_all_tabs)) { expanded = true }
                DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                    tabs.forEach { tab ->
                        DropdownMenuItem(
                            text = { Text(tab.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            modifier = Modifier.semantics { selected = tab.tabId == activeId },
                            onClick = { expanded = false; onSelect(tab.tabId) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReaderMenu(
    tab: TabState, crop: CropMode, landscape: Boolean, split: Boolean,
    onViewMode: () -> Unit, onNight: () -> Unit, onThumbnails: () -> Unit,
    onCrop: (CropMode) -> Unit, onSplit: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        ReaderIcon("⋮", stringResource(R.string.reader_options)) { expanded = true }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            CropMode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = { Text("Crop margins · ${mode.label}" + if (crop == mode) " ✓" else "") },
                    onClick = { expanded = false; onCrop(mode) },
                )
            }
            if (landscape) DropdownMenuItem(
                text = { Text(if (split) "Exit side-by-side" else "Side-by-side") },
                onClick = { expanded = false; onSplit() },
            )
            DropdownMenuItem(
                text = {
                    Text(stringResource(
                        if (tab.viewMode == ReaderViewMode.CONTINUOUS) R.string.reader_single_page
                        else R.string.reader_continuous,
                    ))
                },
                onClick = { expanded = false; onViewMode() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(if (tab.nightMode) R.string.reader_night_off else R.string.reader_night_on)) },
                onClick = { expanded = false; onNight() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.reader_thumbnails)) },
                onClick = { expanded = false; onThumbnails() },
            )
        }
    }
}

@Composable
internal fun ReaderIcon(symbol: String, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(onClick, modifier = Modifier.semantics { contentDescription = label }, enabled = enabled) {
        Text(symbol, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun EmptyReader(recent: List<RecentPdf>, onAdd: () -> Unit, onOpen: (RecentPdf) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        item {
            Column(
                Modifier.padding(vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.no_pdfs), style = MaterialTheme.typography.headlineSmall)
                Button(onAdd) { Text(stringResource(R.string.open_pdf)) }
            }
        }
        if (recent.isNotEmpty()) {
            item {
                Text(
                    stringResource(R.string.reader_recent),
                    Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            items(recent, key = { it.pdfKey.value }) { pdf ->
                Column(
                    Modifier.fillMaxWidth().clickable { onOpen(pdf) }.padding(horizontal = 24.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(pdf.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (pdf.pageCount > 0) {
                        val page = pdf.lastPage.coerceIn(0, pdf.pageCount - 1) + 1
                        Text(stringResource(R.string.reader_recent_progress, page, pdf.pageCount))
                        LinearProgressIndicator({ page.toFloat() / pdf.pageCount }, Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}

@Composable
internal fun JumpDialog(currentPage: Int, count: Int, onDismiss: () -> Unit, onJump: (Int) -> Unit) {
    var input by remember { mutableStateOf((currentPage + 1).toString()) }
    val page = input.toIntOrNull()?.takeIf { it in 1..count }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.reader_jump)) },
        text = {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = { Text(stringResource(R.string.reader_page_range, count)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { page?.let { onJump(it - 1) } }, enabled = page != null) {
                Text(stringResource(R.string.reader_go))
            }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}


@Composable
private fun SplitReader(
    tabs: List<TabState>,
    left: TabState,
    right: TabState,
    activeDocument: PdfDocumentHandle,
    viewModel: ReaderViewModel,
    crop: CropMode,
    onRight: (String) -> Unit,
    primary: @Composable () -> Unit,
) {
    var fraction by rememberSaveable { mutableStateOf(0.5f) }
    val same = left.pdfKey == right.pdfKey
    val opened = key(right.pdfKey, same) {
        val other by produceState<Pair<Boolean, PdfDocumentHandle?>>(false to null) {
            if (!same) value = true to viewModel.openPane(right.pdfKey)
        }
        other
    }
    DisposableEffect(right.pdfKey, same) {
        onDispose { if (!same) viewModel.releasePane(right.pdfKey) }
    }
    val document = if (same) activeDocument else opened.second
    val positions = viewModel.secondaryPositions
    val pans = viewModel.secondaryPans
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = with(LocalDensity.current) { maxWidth.toPx().coerceAtLeast(1f) }
        Row(Modifier.fillMaxSize()) {
            Column(Modifier.weight(fraction)) {
                PanePicker(tabs, left, viewModel::switch)
                Box(Modifier.weight(1f)) { primary() }
            }
            Box(Modifier.width(12.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant)
                .pointerInput(width) {
                    detectHorizontalDragGestures { change, pixels ->
                        change.consume()
                        fraction = (fraction + pixels / width).coerceIn(0.25f, 0.75f)
                    }
                })
            Column(Modifier.weight(1f - fraction)) {
                PanePicker(tabs, right, onRight)
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    if (document == null) {
                        if (opened.first) Text(stringResource(R.string.reader_open_error))
                        else CircularProgressIndicator()
                    } else key(right.tabId, document) {
                        val pane = (positions[right.tabId] ?: right).copy(viewMode = right.viewMode, nightMode = right.nightMode)
                        ReaderPages(
                            document, pane, pans[right.tabId] ?: 0f,
                            viewModel.searches[right.tabId] ?: SearchState(), null, {}, crop, false, {},
                            onPosition = { page, offset ->
                                positions[right.tabId] = (positions[right.tabId] ?: pane).copy(page = page, scrollOffsetPx = offset)
                                if (!same) viewModel.updateOtherPosition(right.tabId, document.pageCount, page, offset)
                            },
                            onZoom = { zoom, offset ->
                                positions[right.tabId] = (positions[right.tabId] ?: pane).copy(zoom = zoom)
                                pans[right.tabId] = offset
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PanePicker(tabs: List<TabState>, tab: TabState, choose: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton({ expanded = true }) { Text(tab.title + " ▾", maxLines = 1, overflow = TextOverflow.Ellipsis) }
        DropdownMenu(expanded, { expanded = false }) {
            tabs.forEach { option ->
                DropdownMenuItem(text = { Text(option.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    onClick = { expanded = false; choose(option.tabId) })
            }
        }
    }
}
