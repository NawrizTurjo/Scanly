package dev.turjo.paperless.feature.library

import android.content.Context
import android.content.ContextWrapper
import android.text.format.DateUtils
import androidx.activity.OnBackPressedCallback
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import dev.turjo.paperless.core.model.DocumentSummary
import dev.turjo.paperless.core.model.FolderSummary
import dev.turjo.paperless.core.model.LibrarySort
import java.io.File
import dev.turjo.paperless.core.designsystem.R as DesignR

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    modifier: Modifier = Modifier,
    onScan: () -> Unit = {},
    onOpenDocument: (String) -> Unit = {},
) {
    val model: LibraryViewModel = hiltViewModel()
    val state by model.uiState.collectAsStateWithLifecycle()
    val filter by model.filter.collectAsStateWithLifecycle()
    val selection by model.selection.collectAsStateWithLifecycle()
    val listLayout by model.listLayout.collectAsStateWithLifecycle()
    val folders by model.folderList.collectAsStateWithLifecycle()
    val deleted by model.deleted.collectAsStateWithLifecycle()
    var folderMode by rememberSaveable { mutableStateOf(false) }
    var bin by rememberSaveable { mutableStateOf(false) }
    var move by rememberSaveable { mutableStateOf(false) }
    var create by rememberSaveable { mutableStateOf(false) }
    val currentFolder = folders.firstOrNull { it.folder.id == filter.folderId }?.folder
    val childFolders = folders.filter { it.folder.parentId == filter.folderId }
    val folderGrid = folderMode && filter.folderId == null
    val selecting = selection.isNotEmpty()
    val snackbar = remember { SnackbarHostState() }
    val moved = stringResource(R.string.library_moved_to_bin)
    val undo = stringResource(R.string.library_undo)
    val failure = stringResource(R.string.detail_error)
    LaunchedEffect(model, snackbar, moved, undo, failure) {
        model.notices.collect { notice ->
            when (notice) {
                is LibraryNotice.Deleted -> {
                    val result = snackbar.showSnackbar(
                        moved, undo, withDismissAction = true, duration = SnackbarDuration.Long,
                    )
                    if (result == SnackbarResult.ActionPerformed) model.undoDelete(notice.ids)
                }
                LibraryNotice.Error -> snackbar.showSnackbar(failure)
            }
        }
    }

    val back: () -> Unit = {
        when {
            selecting -> model.clearSelection()
            bin -> bin = false
            filter.folderId != null -> model.updateFilter {
                it.copy(folderId = currentFolder?.parentId, query = "")
            }
            else -> folderMode = false
        }
    }
    LibraryBackHandler(selecting || bin || filter.folderId != null || folderMode, onBack = back)

    val appBarState = key(filter.folderId, folderMode, bin) { rememberTopAppBarState() }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(appBarState)
    Scaffold(
        modifier = modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            when {
                selecting -> TopAppBar(
                    title = {
                        Text(pluralStringResource(R.plurals.library_selected, selection.size, selection.size))
                    },
                    navigationIcon = { LibraryBackButton(back) },
                    actions = {
                        TextButton(onClick = { move = true }) { Text(stringResource(R.string.library_move)) }
                        val favorite = if (selection.values.all { it }) {
                            R.string.detail_unfavorite
                        } else R.string.detail_favorite
                        LibraryMenu(R.string.detail_more) {
                            DropdownMenuItem(
                                text = { Text(stringResource(favorite)) },
                                onClick = { it(); model.favoriteSelected() },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.detail_delete)) },
                                onClick = { it(); model.deleteSelected() },
                            )
                        }
                    },
                )
                bin -> TopAppBar(
                    title = { Text(stringResource(R.string.library_bin)) },
                    navigationIcon = { LibraryBackButton(back) },
                )
                else -> Column {
                    LargeTopAppBar(
                        title = {
                            Text(
                                currentFolder?.name ?: stringResource(R.string.library_title),
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        },
                        navigationIcon = {
                            if (filter.folderId != null) LibraryBackButton(back)
                        },
                        actions = {
                            if (!folderGrid) {
                                val layout = if (listLayout) R.string.library_grid else R.string.library_list
                                TextButton(onClick = model::toggleLayout) { Text(stringResource(layout)) }
                                LibraryMenu(R.string.library_sort) { close ->
                                    for (sort in LibrarySort.entries) {
                                        val label = when (sort) {
                                            LibrarySort.NAME -> R.string.library_by_name
                                            LibrarySort.UPDATED_DESC -> R.string.library_by_updated
                                            LibrarySort.CREATED_DESC -> R.string.library_by_created
                                        }
                                        DropdownMenuItem(
                                            text = { Text(stringResource(label)) },
                                            leadingIcon = { if (sort == filter.sort) Text("✓") },
                                            onClick = { close(); model.updateFilter { it.copy(sort = sort) } },
                                        )
                                    }
                                }
                            }
                            LibraryMenu(R.string.detail_more) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.library_bin)) },
                                    onClick = { it(); bin = true },
                                )
                            }
                        },
                        scrollBehavior = scrollBehavior,
                    )
                    if (!folderGrid) {
                        AnimatedVisibility(scrollBehavior.state.collapsedFraction < 0.9f) {
                            OutlinedTextField(
                                value = filter.query,
                                onValueChange = { query -> model.updateFilter { it.copy(query = query) } },
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                                label = { Text(stringResource(R.string.library_search)) },
                                singleLine = true,
                            )
                        }
                    }
                    if (filter.folderId == null) {
                        Row(
                            Modifier.padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            FilterChip(!folderMode && !filter.favouritesOnly, {
                                folderMode = false
                                model.updateFilter { it.copy(favouritesOnly = false) }
                            }, label = { Text(stringResource(R.string.library_all)) })
                            FilterChip(!folderMode && filter.favouritesOnly, {
                                folderMode = false
                                model.updateFilter { it.copy(favouritesOnly = true) }
                            }, label = { Text(stringResource(R.string.library_favourites)) })
                            FilterChip(folderMode, {
                                folderMode = true
                                model.updateFilter { it.copy(favouritesOnly = false, query = "") }
                            }, label = { Text(stringResource(R.string.library_folders)) })
                        }
                    }
                }
            }
        },
        floatingActionButton = {
            if (!bin && !selecting) {
                if (folderGrid || (currentFolder != null && currentFolder.parentId == null)) {
                    ExtendedFloatingActionButton(onClick = { create = true }) {
                        Text(stringResource(R.string.library_new_folder))
                    }
                } else if (filter.folderId == null) {
                    ExtendedFloatingActionButton(
                        onClick = onScan,
                        icon = { Icon(painterResource(DesignR.drawable.ic_scan), contentDescription = null) },
                        text = { Text(stringResource(R.string.scan)) },
                    )
                }
            }
        },
    ) { padding ->
        val content = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
        val visibleFolders = if (folderMode && filter.query.isBlank()) childFolders else emptyList()
        when {
            bin -> RecycleBin(content, deleted, model::restore, model::deleteForever)
            !folderGrid && (state.isLoading || state.filter.folderId != filter.folderId) -> Box(content, contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            visibleFolders.isEmpty() && (folderGrid || state.documents.isEmpty()) -> {
                if (!folderMode && filter.query.isBlank() && !filter.favouritesOnly) EmptyLibrary(content)
                else Box(content, contentAlignment = Alignment.Center) {
                    val label = if (folderGrid) R.string.library_no_folders else R.string.no_documents
                    Text(stringResource(label))
                }
            }
            else -> key(folderMode, filter.folderId, listLayout) {
                LazyVerticalGrid(
                    columns = if (!folderGrid && listLayout) GridCells.Fixed(1) else GridCells.Adaptive(160.dp),
                    modifier = content,
                    contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 88.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(visibleFolders, key = { "folder-" + it.folder.id }) { summary ->
                        FolderCard(
                            summary,
                            onOpen = {
                                model.clearSelection()
                                model.updateFilter { it.copy(folderId = summary.folder.id, query = "") }
                            },
                            onRename = { model.renameFolder(summary.folder.id, it) },
                            onDelete = { model.deleteFolder(summary.folder.id) },
                        )
                    }
                    if (!folderGrid) items(state.documents, key = DocumentSummary::id) { document ->
                        DocumentCard(
                            document, listLayout, document.id in selection,
                            onClick = {
                                if (selecting) model.toggleSelection(document) else onOpenDocument(document.id)
                            },
                            onLongClick = { model.toggleSelection(document) },
                        )
                    }
                }
            }
        }
    }

    if (create) FolderNameDialog(onDismiss = { create = false }) { name ->
        model.createFolder(name, filter.folderId)
        create = false
    }
    if (move && selecting) MoveToFolderDialog(
        folders, onDismiss = { move = false },
        onSelect = { move = false; model.moveSelected(it) },
        onCreate = { move = false; model.moveSelectedToNewFolder(it) },
    )
}

@Composable
private fun LibraryBackHandler(enabled: Boolean, onBack: () -> Unit) {
    val context = LocalContext.current
    val dispatcher = remember(context) { context.findBackDispatcherOwner()?.onBackPressedDispatcher }
    val lifecycle = LocalLifecycleOwner.current
    val currentBack by rememberUpdatedState(onBack)
    val callback = remember {
        object : OnBackPressedCallback(enabled) {
            override fun handleOnBackPressed() = currentBack()
        }
    }
    SideEffect { callback.isEnabled = enabled }
    DisposableEffect(dispatcher, lifecycle, callback) {
        dispatcher?.addCallback(lifecycle, callback)
        onDispose { callback.remove() }
    }
}

private tailrec fun Context.findBackDispatcherOwner(): OnBackPressedDispatcherOwner? = when (this) {
    is OnBackPressedDispatcherOwner -> this
    is ContextWrapper -> baseContext.findBackDispatcherOwner()
    else -> null
}

@Composable
private fun LibraryBackButton(onClick: () -> Unit) {
    GlyphButton(R.string.detail_back_icon, stringResource(R.string.detail_back), onClick)
}

@Composable
private fun LibraryMenu(label: Int, items: @Composable ColumnScope.(() -> Unit) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        if (label == R.string.detail_more) {
            GlyphButton(R.string.detail_more_icon, stringResource(label)) { open = true }
        } else {
            TextButton(onClick = { open = true }) { Text(stringResource(label)) }
        }
        DropdownMenu(open, { open = false }) { items { open = false } }
    }
}

@Composable
private fun EmptyLibrary(modifier: Modifier) {
    Column(
        modifier = modifier.padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(R.string.no_documents),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Text(
            stringResource(R.string.scan_prompt),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun DocumentCard(
    document: DocumentSummary,
    listLayout: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val cover = remember(document.coverThumbPath) { document.coverThumbPath?.let(::File) }
    val relativeDate = DateUtils.getRelativeTimeSpanString(
        document.updatedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS,
    )
    val count = pluralStringResource(R.plurals.page_count, document.pageCount, document.pageCount)
    val cardColor = if (selected) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.surfaceContainerHighest
    val selectLabel = stringResource(R.string.library_select_document)

    Card(
        modifier = Modifier.fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClickLabel = selectLabel, onLongClick = onLongClick)
            .semantics { this.selected = selected },
        colors = CardDefaults.cardColors(containerColor = cardColor),
    ) {
        val image: @Composable (Modifier) -> Unit = { modifier ->
            AsyncImage(
                model = cover, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
            )
        }
        val metadata: @Composable () -> Unit = {
            Column(Modifier.padding(12.dp)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        document.title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    if (selected) Text("✓")
                    if (document.isFavorite) {
                        val favorite = stringResource(R.string.favorite)
                        Text("★", Modifier.semantics { contentDescription = favorite })
                    }
                }
                Text(
                    stringResource(R.string.document_metadata, count, relativeDate),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (listLayout) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                image(Modifier.size(width = 56.dp, height = 72.dp))
                Box(Modifier.weight(1f)) { metadata() }
            }
        } else {
            image(Modifier.fillMaxWidth().aspectRatio(1f / 1.414f))
            metadata()
        }
    }
}
