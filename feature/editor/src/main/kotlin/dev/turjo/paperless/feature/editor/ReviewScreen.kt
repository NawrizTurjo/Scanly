package dev.turjo.paperless.feature.editor

import dev.turjo.paperless.core.designsystem.ScanlyScaffold
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import dev.turjo.paperless.core.designsystem.DropdownMenu
import dev.turjo.paperless.core.designsystem.AlertDialog
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import dev.turjo.paperless.core.data.isIdComposite
import dev.turjo.paperless.core.data.defaultTitle
import dev.turjo.paperless.core.designsystem.PageBatchSheet
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyGridState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(
    draftDocumentId: String,
    onViewPage: (pageId: String) -> Unit,
    onAddMore: () -> Unit,
    onSaved: (documentId: String) -> Unit,
    onBack: () -> Unit,
    onExport: (draftDocumentId: String) -> Unit = {},
) {
    val vm = hiltViewModel<ReviewViewModel, ReviewViewModel.Factory>(key = draftDocumentId) { it.create(draftDocumentId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var overflow by remember { mutableStateOf(false) }
    var split by rememberSaveable { mutableStateOf(false) }
    var twoSided by rememberSaveable { mutableStateOf(state.duplex) }
    var discard by rememberSaveable { mutableStateOf(false) }
    var save by rememberSaveable { mutableStateOf(false) }
    var batchSheet by rememberSaveable { mutableStateOf(false) }
    var title by rememberSaveable { mutableStateOf("") }
    val canAct = !state.loading && !state.changing && state.session != null && !state.finishPending

    val firstGroupCount = state.groups.firstOrNull()?.kept?.size ?: state.pages.size
    val groupNamesValid = state.groups.all { it.title.isNotBlank() }

    LaunchedEffect(state.savedId, state.discarded) {
        state.savedId?.let(onSaved)
        if (state.discarded) onBack()
    }
    BackHandler(enabled = state.changing) {}
    EditorMessage(state.message, snackbar) { vm.message(null) }

    ScanlyScaffold(
        topBar = {
            TopAppBar(
                title = { Text(pluralStringResource(R.plurals.review_pages, state.pages.size, state.pages.size)) },
                navigationIcon = { SymbolButton("←", R.string.editor_back, !state.changing, onBack) },
                actions = {
                    Box {
                        SymbolButton("⋮", R.string.review_options, canAct) { overflow = true }
                        DropdownMenu(overflow, { overflow = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.review_split_at_blanks)) },
                                enabled = state.pages.isNotEmpty(),
                                onClick = {
                                    overflow = false
                                    twoSided = state.duplex
                                    split = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.review_export)) },
                                enabled = state.pages.isNotEmpty(),
                                onClick = {
                                    overflow = false
                                    onExport(draftDocumentId)
                                },
                            )
                            DropdownMenuItem(text = { Text(stringResource(R.string.review_discard_all)) }, onClick = {
                                overflow = false
                                discard = true
                            })
                        }
                    }
                },
            )
        },
        bottomBar = {
            BottomAppBar(
                contentPadding = PaddingValues(horizontal = 8.dp),
            ) {
                TextButton({ batchSheet = true }, enabled = canAct && state.pages.any { !it.page.isIdComposite }) {
                    Text(stringResource(R.string.review_apply_all))
                }
                TextButton(onAddMore, enabled = canAct) { Text(stringResource(R.string.review_add_more)) }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = {
                        if (state.finishPending || (state.groups.isNotEmpty() && state.session?.targetDocumentId == null)) {
                            vm.save("")
                        } else {
                            title = defaultTitle(System.currentTimeMillis())
                            save = true
                        }
                    },
                    enabled = !state.changing && state.finishPending || canAct && state.pages.isNotEmpty() && groupNamesValid,
                ) { Text(stringResource(R.string.review_save)) }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            ReviewGrid(
                state.pages, state.groups, canAct, onViewPage, vm::reorder, vm::rotate, vm::delete,
                vm::splitHere, vm::join, vm::renameGroup, vm::restoreSeparator,
            )
            when {
                state.loading || state.changing -> Box(Modifier.align(Alignment.Center)) { EditorProgress() }
                state.session == null && !state.discarded && state.savedId == null ->
                    Text(stringResource(R.string.review_load_error), Modifier.align(Alignment.Center))
                state.session != null && state.pages.isEmpty() ->
                    Text(stringResource(R.string.review_empty), Modifier.align(Alignment.Center))
            }
        }
    }

    if (split) {
        AlertDialog(
            onDismissRequest = { split = false },
            title = { Text(stringResource(R.string.review_split_at_blanks)) },
            text = {
                Column {
                    Text(stringResource(R.string.review_split_body))
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(
                            value = twoSided, role = Role.Checkbox, onValueChange = { twoSided = it },
                        ),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Checkbox(checked = twoSided, onCheckedChange = null)
                        Text(stringResource(R.string.review_two_sided))
                    }
                }
            },
            dismissButton = { TextButton({ split = false }) { Text(stringResource(R.string.editor_cancel)) } },
            confirmButton = {
                TextButton(
                    onClick = {
                        split = false
                        vm.splitAtBlanks(twoSided)
                    },
                    enabled = canAct,
                ) { Text(stringResource(R.string.review_split)) }
            },
        )
    }
    if (discard) {
        ConfirmDialog(
            R.string.review_discard_all, R.string.review_discard_body, R.string.editor_discard,
            onDismiss = { discard = false },
        ) {
            discard = false
            vm.discard()
        }
    }
    if (save && state.session != null) {
        val append = state.session?.targetDocumentId != null
        AlertDialog(
            onDismissRequest = { save = false },
            title = { Text(stringResource(if (append) R.string.review_append_title else R.string.review_save_title)) },
            text = {
                if (append) {
                    Text(pluralStringResource(
                        R.plurals.review_append, firstGroupCount, firstGroupCount, state.targetTitle.orEmpty(),
                    ))
                } else {
                    OutlinedTextField(
                        value = title, onValueChange = { title = it }, singleLine = true,
                        label = { Text(stringResource(R.string.review_title_field)) },
                    )
                }
            },
            dismissButton = { TextButton({ save = false }) { Text(stringResource(R.string.editor_cancel)) } },
            confirmButton = {
                TextButton(
                    onClick = {
                        save = false
                        vm.save(title.trim())
                    },
                    enabled = canAct && if (append) state.targetTitle != null else title.isNotBlank(),
                ) { Text(stringResource(R.string.review_save)) }
            },
        )
    }
    val editablePages = state.pages.map { it.page }.filterNot { it.isIdComposite }
    if (batchSheet && editablePages.isNotEmpty()) {
        PageBatchSheet(
            title = stringResource(R.string.review_apply_all),
            pages = editablePages,
            onDismiss = { batchSheet = false },
            onApply = {
                batchSheet = false
                vm.applyToAllPages(it)
            },
        )
    }
}

@Composable
private fun ReviewGrid(
    pages: List<ReviewPage>,
    groups: List<ReviewGroup>,
    enabled: Boolean,
    onView: (String) -> Unit,
    onReorder: (List<String>) -> Unit,
    onRotate: (String, Int) -> Unit,
    onDelete: (String) -> Unit,
    onSplit: (String) -> Unit,
    onJoin: (Int) -> Unit,
    onRename: (Int, String) -> Unit,
    onRestore: (String) -> Unit,
) {
    val sections = groups.ifEmpty { listOf(ReviewGroup("", pages.map { it.page.id })) }
    val repositoryIds = sections.flatMap { it.kept }
    val sectionById = sections.flatMapIndexed { index, group -> group.kept.map { it to index } }.toMap()
    var draggedIds by remember { mutableStateOf<List<String>?>(null) }
    var awaitingIds by remember { mutableStateOf<List<String>?>(null) }
    LaunchedEffect(repositoryIds, enabled) {
        if (awaitingIds == repositoryIds || enabled) awaitingIds = null
    }
    val ids = draggedIds ?: awaitingIds ?: repositoryIds
    val byId = pages.associateBy { it.page.id }
    val grid = rememberLazyGridState()
    val reorder = rememberReorderableLazyGridState(grid) { from, to ->
        val sourceSection = sectionById[from.key]
        if (sourceSection != null && sourceSection == sectionById[to.key]) {
            draggedIds = (draggedIds ?: repositoryIds).toMutableList().apply {
                val source = indexOf(from.key)
                val destination = indexOf(to.key)
                if (source >= 0 && destination >= 0) add(destination, removeAt(source))
            }
        }
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp), state = grid, modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        sections.forEachIndexed { index, group ->
            if (groups.isNotEmpty()) {
                item(key = "group-$index", span = { GridItemSpan(maxLineSpan) }) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                pluralStringResource(
                                    R.plurals.review_group_header, group.kept.size, index + 1, group.kept.size,
                                ),
                                Modifier.weight(1f),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            if (index > 0) {
                                TextButton({ onJoin(index) }, enabled = enabled) {
                                    Text(stringResource(R.string.review_join))
                                }
                            }
                        }
                        OutlinedTextField(
                            value = group.title,
                            onValueChange = { onRename(index, it) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            enabled = enabled,
                            label = { Text(stringResource(R.string.review_title_field)) },
                        )
                    }
                }
            }
            val kept = ids.filter { sectionById[it] == index }
            items(kept.mapNotNull(byId::get), key = { it.page.id }) { item ->
                val id = item.page.id
                ReorderableItem(reorder, id) { _ ->
                    val handle = Modifier.longPressDraggableHandle(
                        enabled = enabled,
                        onDragStarted = { draggedIds = repositoryIds },
                        onDragStopped = {
                            draggedIds?.takeIf { it != repositoryIds }?.let {
                                awaitingIds = it
                                onReorder(it)
                            }
                            draggedIds = null
                        },
                    )
                    ReviewTile(
                        item, ids.indexOf(id) + 1, enabled,
                        view = { onView(id) }, rotate = { onRotate(id, it) }, delete = { onDelete(id) },
                        split = { onSplit(id) }.takeIf { id != kept.firstOrNull() },
                        dragHandle = {
                            val dragLabel = stringResource(R.string.review_reorder)
                            Box(
                                handle.size(48.dp).semantics { contentDescription = dragLabel },
                                contentAlignment = Alignment.Center,
                            ) { Text("⠿") }
                        },
                    )
                }
            }
            items(group.dropped.mapNotNull(byId::get), key = { it.page.id }) { item ->
                Column {
                    AsyncImage(
                        item.thumbnail,
                        stringResource(R.string.review_blank_separator),
                        Modifier.fillMaxWidth().aspectRatio(0.78f).padding(6.dp).alpha(0.45f),
                        contentScale = ContentScale.Fit,
                    )
                    TextButton({ onRestore(item.page.id) }, enabled = enabled) {
                        Text(stringResource(R.string.review_restore))
                    }
                }
            }
        }
    }
}

@Composable
private fun ReviewTile(
    item: ReviewPage,
    number: Int,
    enabled: Boolean,
    dragHandle: @Composable () -> Unit,
    view: () -> Unit,
    rotate: (Int) -> Unit,
    delete: () -> Unit,
    split: (() -> Unit)?,
) {
    val context = LocalContext.current
    val request = remember(context, item.thumbnail, item.modified) {
        val key = "${item.thumbnail.path}:${item.modified}"
        ImageRequest.Builder(context).data(item.thumbnail).memoryCacheKey(key).diskCacheKey(key).build()
    }
    var menu by remember { mutableStateOf(false) }
    val background = MaterialTheme.colorScheme.surfaceContainerHighest
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(background)
            .clickable(enabled = enabled, onClick = view),
    ) {
        // Controls sit below the thumbnail so they never cover the page corners.
        Box(Modifier.fillMaxWidth().aspectRatio(0.78f)) {
            AsyncImage(
                request, stringResource(R.string.review_page_number, number),
                Modifier.padding(6.dp).fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
            Row(
                Modifier.align(Alignment.TopEnd).padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (item.page.glareWarning) {
                    val warning = stringResource(R.string.editor_glare_warning)
                    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.errorContainer) {
                        Text("!", Modifier.padding(horizontal = 8.dp).semantics { contentDescription = warning })
                    }
                }
                if (item.busy) EditorProgress()
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(number.toString(), Modifier.padding(start = 12.dp).weight(1f))
            dragHandle()
            Box {
                SymbolButton("⋮", R.string.review_page_options, enabled) { menu = true }
                DropdownMenu(menu, { menu = false }) {
                    if (!item.page.isIdComposite) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.editor_rotate_left)) }, onClick = {
                            menu = false
                            rotate(-90)
                        })
                        DropdownMenuItem(text = { Text(stringResource(R.string.editor_rotate_right)) }, onClick = {
                            menu = false
                            rotate(90)
                        })
                    }
                    if (split != null) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.review_split_here)) }, onClick = {
                            menu = false
                            split()
                        })
                    }
                    DropdownMenuItem(text = { Text(stringResource(R.string.editor_delete)) }, onClick = {
                        menu = false
                        delete()
                    })
                }
            }
        }
    }
}
