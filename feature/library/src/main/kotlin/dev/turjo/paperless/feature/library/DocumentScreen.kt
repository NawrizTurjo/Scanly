package dev.turjo.paperless.feature.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
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
    onOpenPage: (pageId: String) -> Unit,
    onAddPages: (documentId: String, insertAt: Int?) -> Unit,
    onExport: (documentId: String) -> Unit,
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
    var move by rememberSaveable(documentId) { mutableStateOf(false) }
    var shownCopies by rememberSaveable(documentId) { mutableIntStateOf(0) }
    val duplicateDone = stringResource(R.string.library_duplicate_done)
    LaunchedEffect(state.duplicationCount) {
        if (state.duplicationCount > shownCopies) {
            shownCopies = state.duplicationCount
            snackbar.showSnackbar(duplicateDone)
        }
    }
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

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    GlyphButton(R.string.detail_back_icon, stringResource(R.string.detail_back), onBack)
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
                        TextButton(enabled = !state.sharing && !state.savingOrder, onClick = {
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
                        TextButton(enabled = !state.savingOrder, onClick = { addMenu = true }) {
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
                    TextButton(enabled = !state.savingOrder, onClick = { onExport(documentId) }) {
                        Text(stringResource(R.string.detail_export))
                    }
                    Spacer(Modifier.weight(1f))
                    Box {
                        GlyphButton(R.string.detail_more_icon, stringResource(R.string.detail_more)) { moreMenu = true }
                        DropdownMenu(moreMenu, { moreMenu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.library_duplicate)) },
                                onClick = { moreMenu = false; model.duplicate() },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.library_move_to_folder)) },
                                onClick = { moreMenu = false; move = true },
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
        val content = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
        Column(content) {
            if (state.sharing) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (document != null) {
                DocumentPages(
                    document, state, busy, model, onOpenPage,
                    onDelete = { id ->
                        if (document.pages.size == 1) deleteLastId = id else model.deletePage(id)
                    },
                )
            } else if (!state.loaded) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
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
    onOpenPage: (String) -> Unit,
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
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        itemsIndexed(order, key = { _, id -> id }) { index, id ->
            val page = pagesById.getValue(id)
            ReorderableItem(reorderable, key = id) { dragging ->
                PageCard(
                    page, index + 1, id in busy, dragging, model,
                    dragHandle = Modifier.longPressDraggableHandle(
                        enabled = !state.savingOrder && !state.sharing,
                        onDragStopped = {
                            if (order != savedOrder) model.reorder(order)
                        },
                    ),
                    onOpen = { onOpenPage(id) },
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
    ElevatedCard(elevation = CardDefaults.elevatedCardElevation(if (dragging) 8.dp else 1.dp)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(0.85f).clickable(onClickLabel = pageLabel, onClick = onOpen),
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(request, contentDescription = pageLabel, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
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
                GlyphButton(R.string.detail_more_icon, stringResource(R.string.detail_page_actions)) { menu = true }
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
internal fun GlyphButton(glyph: Int, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.semantics { contentDescription = label }) {
        Text(stringResource(glyph), Modifier.clearAndSetIconText(), style = MaterialTheme.typography.headlineSmall)
    }
}

private fun Modifier.clearAndSetIconText() = clearAndSetSemantics {}

@Composable
internal fun CancelButton(onClick: () -> Unit) {
    TextButton(onClick = onClick) { Text(stringResource(R.string.detail_cancel)) }
}
