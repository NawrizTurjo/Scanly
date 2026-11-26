package dev.turjo.paperless.feature.library

import dev.turjo.paperless.core.designsystem.LocalGlassEnabled
import dev.turjo.paperless.core.designsystem.glassEdge
import dev.turjo.paperless.core.designsystem.glassContainer
import dev.turjo.paperless.core.designsystem.ScanlyScaffold
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import dev.turjo.paperless.core.designsystem.DropdownMenu
import dev.turjo.paperless.core.designsystem.AlertDialog
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import dev.turjo.paperless.core.data.isIdComposite
import dev.turjo.paperless.core.designsystem.PageBatchSheet
import dev.turjo.paperless.core.model.DocumentWithPages
import dev.turjo.paperless.core.model.Page
import dev.turjo.paperless.core.pdf.ShareIntents
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyGridState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentScreen(
    documentId: String,
    onBack: () -> Unit,
    onViewPage: (pageId: String) -> Unit,
    onAddPages: (documentId: String, insertAt: Int?) -> Unit,
    onExport: (documentId: String) -> Unit,
    onOpenInReader: ((String) -> Unit)? = null,
) {
    val model = hiltViewModel<DocumentViewModel, DocumentViewModel.Factory>(key = documentId) {
        it.create(documentId)
    }
    val state by model.state.collectAsStateWithLifecycle()
    val folders by model.folderList.collectAsStateWithLifecycle()
    val busy by model.busyPageIds.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val error = stringResource(R.string.detail_error)
    val document = state.document
    var editTags by rememberSaveable(documentId) { mutableStateOf(false) }
    var move by rememberSaveable(documentId) { mutableStateOf(false) }
    var shownCopies by rememberSaveable(documentId) { mutableIntStateOf(0) }
    val duplicateDone = stringResource(R.string.library_duplicate_done)
    LaunchedEffect(state.duplicationCount) {
        if (state.duplicationCount > shownCopies) {
            shownCopies = state.duplicationCount
            snackbar.showSnackbar(duplicateDone)
        }
    }
    var batchSheet by rememberSaveable(documentId) { mutableStateOf(false) }
    var rename by rememberSaveable(documentId) { mutableStateOf(false) }
    var pickPosition by rememberSaveable(documentId) { mutableStateOf(false) }
    var deleteDocument by rememberSaveable(documentId) { mutableStateOf(false) }
    var deleteLastId by rememberSaveable(documentId) { mutableStateOf<String?>(null) }

    LaunchedEffect(state.loaded, document == null) {
        if (state.loaded && document == null) onBack()
    }
    LaunchedEffect(state.error) {
        if (state.error) {
            snackbar.showSnackbar(error)
            model.clearError()
        }
    }

    LibraryBackHandler(state.applyingBatch) {}
    ScanlyScaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    GlyphButton(
                        R.string.detail_back_icon, stringResource(R.string.detail_back),
                        enabled = !state.applyingBatch, onClick = onBack,
                    )
                },
                title = {
                    document?.let {
                        Text(
                            it.document.title,
                            Modifier.clickable(onClickLabel = stringResource(R.string.detail_rename)) {
                                rename = true
                            }.heightIn(min = 48.dp).wrapContentHeight(),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                actions = {
                    document?.let {
                        val label = stringResource(
                            if (it.document.isFavorite) R.string.detail_unfavorite else R.string.detail_favorite,
                        )
                        GlyphButton(
                            if (it.document.isFavorite) R.string.detail_star_full else R.string.detail_star_empty,
                            label, onClick = { model.favorite() },
                        )
                        TextButton(enabled = !state.sharing && !state.savingOrder && !state.applyingBatch, onClick = {
                            model.share { files, mime ->
                                context.startActivity(ShareIntents.forFiles(context, files, mime))
                            }
                        }) {
                            Text(stringResource(R.string.detail_share))
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (document != null) {
                BottomAppBar {
                    var addMenu by remember { mutableStateOf(false) }
                    var moreMenu by remember { mutableStateOf(false) }
                    Box {
                        TextButton(enabled = !state.savingOrder && !state.applyingBatch, onClick = { addMenu = true }) {
                            Text(stringResource(R.string.detail_add_pages))
                        }
                        DropdownMenu(addMenu, { addMenu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.detail_at_end)) },
                                onClick = { addMenu = false; onAddPages(documentId, null) },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.detail_after_page)) },
                                onClick = { addMenu = false; pickPosition = true },
                            )
                        }
                    }
                    TextButton(enabled = !state.savingOrder && !state.applyingBatch, onClick = { onExport(documentId) }) {
                        Text(stringResource(R.string.detail_export))
                    }
                    Spacer(Modifier.weight(1f))
                    Box {
                        GlyphButton(
                            R.string.detail_more_icon, stringResource(R.string.detail_more), enabled = !state.applyingBatch,
                        ) { moreMenu = true }
                        DropdownMenu(moreMenu, { moreMenu = false }) {
                            if (onOpenInReader != null) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.detail_open_in_reader)) },
                                    enabled = !state.savingOrder,
                                    onClick = {
                                        moreMenu = false
                                        onOpenInReader(documentId)
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.detail_apply_all)) },
                                enabled = !state.applyingBatch && !state.savingOrder && document.pages.any { !it.isIdComposite },
                                onClick = {
                                    moreMenu = false
                                    batchSheet = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.library_duplicate)) },
                                onClick = { moreMenu = false; model.duplicate() },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.library_move_to_folder)) },
                                onClick = { moreMenu = false; move = true },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.library_tags)) },
                                onClick = { moreMenu = false; editTags = true },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.detail_delete_document)) },
                                onClick = { moreMenu = false; deleteDocument = true },
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        // The page grid runs under the (glass) bars; its padding keeps the first and last rows clear.
        val content = Modifier.fillMaxSize().consumeWindowInsets(padding)
        Column(content) {
            if (state.sharing || state.applyingBatch) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = padding.calculateTopPadding()))
            }
            if (document != null) {
                DocumentPages(
                    document, state, busy, model, onViewPage, padding,
                    onDelete = { id ->
                        if (document.pages.size == 1) deleteLastId = id else model.deletePage(id)
                    },
                )
            } else if (!state.loaded) {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        }
    }

    val editablePages = document?.pages.orEmpty().filterNot { it.isIdComposite }
    if (batchSheet && editablePages.isNotEmpty()) {
        PageBatchSheet(
            title = stringResource(R.string.detail_apply_all),
            pages = editablePages,
            onDismiss = { batchSheet = false },
            onApply = {
                batchSheet = false
                model.applyToAllPages(it)
            },
        )
    }

    if (editTags && document != null) {
        val tags by model.tags.collectAsStateWithLifecycle()
        EditTagsDialog(model.documentTags, tags, onDismiss = { editTags = false }) { ids, name ->
            model.setTags(ids, name)
            editTags = false
        }
    }

    if (move) MoveToFolderDialog(
        folders, onDismiss = { move = false },
        onSelect = { move = false; model.moveToFolder(it) },
        onCreate = { move = false; model.moveToNewFolder(it) },
    )

    if (rename && document != null) {
        var title by rememberSaveable { mutableStateOf(document.document.title) }
        AlertDialog(
            onDismissRequest = { rename = false },
            title = { Text(stringResource(R.string.detail_rename)) },
            text = {
                OutlinedTextField(
                    value = title, onValueChange = { title = it }, singleLine = true,
                    label = { Text(stringResource(R.string.detail_name)) },
                )
            },
            confirmButton = {
                TextButton(enabled = title.isNotBlank(), onClick = { model.rename(title); rename = false }) {
                    Text(stringResource(R.string.detail_save))
                }
            },
            dismissButton = { CancelButton { rename = false } },
        )
    }
    if (pickPosition && document != null) {
        InsertPositionDialog(document.pages.size, { pickPosition = false }) { position ->
            pickPosition = false
            onAddPages(documentId, position)
        }
    }
    if (deleteDocument || deleteLastId != null) {
        AlertDialog(
            onDismissRequest = { deleteDocument = false; deleteLastId = null },
            title = { Text(stringResource(R.string.detail_delete_document)) },
            text = {
                Text(stringResource(
                    if (deleteLastId != null) R.string.detail_last_page_message else R.string.detail_delete_message,
                ))
            },
            confirmButton = {
                TextButton(onClick = {
                    val pageId = deleteLastId
                    if (pageId != null) model.deletePage(pageId, confirmedLastPage = true)
                    else model.deleteDocument()
                    deleteDocument = false
                    deleteLastId = null
                }) { Text(stringResource(R.string.detail_delete)) }
            },
            dismissButton = { CancelButton { deleteDocument = false; deleteLastId = null } },
        )
    }
}

@Composable
private fun DocumentPages(
    document: DocumentWithPages,
    state: DocumentState,
    busy: Set<String>,
    model: DocumentViewModel,
    onViewPage: (String) -> Unit,
    barPadding: PaddingValues,
    onDelete: (String) -> Unit,
) {
    val pagesById = remember(document.pages) { document.pages.associateBy(Page::id) }
    val savedOrder = document.pages.map(Page::id)
    var order by remember(savedOrder, state.orderRevision) { mutableStateOf(savedOrder) }
    val grid = rememberLazyGridState()
    val reorderable = rememberReorderableLazyGridState(grid) { from, to ->
        val fromId = from.key as String
        val toId = to.key as String
        val fromIndex = order.indexOf(fromId)
        val toIndex = order.indexOf(toId)
        if (fromIndex >= 0 && toIndex >= 0) {
            order = order.toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
        }
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        state = grid,
        contentPadding = PaddingValues(
            start = 12.dp,
            top = barPadding.calculateTopPadding() + 12.dp,
            end = 12.dp,
            bottom = barPadding.calculateBottomPadding() + 12.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        itemsIndexed(order, key = { _, id -> id }) { index, id ->
            val page = pagesById.getValue(id)
            ReorderableItem(reorderable, key = id) { dragging ->
                PageCard(
                    page, index + 1, id in busy, dragging, model,
                    enabled = !state.applyingBatch,
                    dragHandle = Modifier.longPressDraggableHandle(
                        enabled = !state.savingOrder && !state.sharing && !state.applyingBatch,
                        onDragStopped = {
                            if (order != savedOrder) model.reorder(order)
                        },
                    ),
                    onOpen = { onViewPage(id) },
                    onDelete = { onDelete(id) },
                )
            }
        }
    }
}

@Composable
private fun PageCard(
    page: Page,
    number: Int,
    busy: Boolean,
    dragging: Boolean,
    model: DocumentViewModel,
    enabled: Boolean,
    dragHandle: Modifier,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val thumb by produceState<Pair<java.io.File, Long>?>(null, page, busy) {
        value = model.thumbnail(page.id)
    }
    val request = remember(context, thumb) {
        thumb?.let { (file, timestamp) ->
            ImageRequest.Builder(context).data(file)
                .memoryCacheKey("${file.path}:$timestamp").diskCacheKey("${file.path}:$timestamp").build()
        }
    }
    val pageLabel = stringResource(R.string.detail_page_number, number)
    var menu by remember { mutableStateOf(false) }
    ElevatedCard(
        modifier = Modifier.glassEdge(MaterialTheme.shapes.medium),
        elevation = CardDefaults.elevatedCardElevation(if (dragging) 8.dp else if (LocalGlassEnabled.current) 0.dp else 1.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = glassContainer(MaterialTheme.colorScheme.surfaceContainerLow),
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(0.85f).clickable(enabled = enabled, onClickLabel = pageLabel, onClick = onOpen),
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                request, pageLabel, Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
            if (busy) CircularProgressIndicator(Modifier.size(36.dp))
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            val reorderLabel = stringResource(R.string.detail_reorder)
            Box(
                dragHandle.size(48.dp).semantics { contentDescription = reorderLabel },
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(R.string.detail_reorder_icon), Modifier.clearAndSetIconText())
            }
            Text(number.toString(), Modifier.weight(1f), maxLines = 1)
            Box {
                GlyphButton(R.string.detail_more_icon, stringResource(R.string.detail_page_actions), enabled) { menu = true }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.detail_rotate_left)) },
                        onClick = { menu = false; model.rotate(page.id, -90) },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.detail_rotate_right)) },
                        onClick = { menu = false; model.rotate(page.id, 90) },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.detail_delete)) },
                        onClick = { menu = false; onDelete() },
                    )
                }
            }
        }
    }
}

@Composable
private fun InsertPositionDialog(count: Int, onDismiss: () -> Unit, onSelect: (Int) -> Unit) {
    var input by rememberSaveable { mutableStateOf("1") }
    val position = input.toIntOrNull()?.let { insertAfterPage(it, count) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.detail_after_page)) },
        text = {
            OutlinedTextField(
                value = input, onValueChange = { input = it }, singleLine = true,
                label = { Text(stringResource(R.string.detail_position, count)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        },
        confirmButton = {
            TextButton(enabled = position != null, onClick = { position?.let(onSelect) }) {
                Text(stringResource(R.string.detail_add_pages))
            }
        },
        dismissButton = { CancelButton(onDismiss) },
    )
}

@Composable
internal fun GlyphButton(glyph: Int, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.semantics { contentDescription = label }) {
        Text(stringResource(glyph), Modifier.clearAndSetIconText(), style = MaterialTheme.typography.headlineSmall)
    }
}

private fun Modifier.clearAndSetIconText() = clearAndSetSemantics {}

@Composable
internal fun CancelButton(onClick: () -> Unit) {
    TextButton(onClick = onClick) { Text(stringResource(R.string.detail_cancel)) }
}
