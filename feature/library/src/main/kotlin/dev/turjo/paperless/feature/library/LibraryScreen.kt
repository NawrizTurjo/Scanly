package dev.turjo.paperless.feature.library

import android.content.Context
import android.content.ContextWrapper
import android.text.format.DateUtils
import androidx.activity.OnBackPressedCallback
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
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
import dev.turjo.paperless.core.model.PdfKey
import dev.turjo.paperless.core.model.TagSummary
import dev.turjo.paperless.core.pdf.ShareIntents
import java.io.File
import dev.turjo.paperless.core.designsystem.R as DesignR

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    modifier: Modifier = Modifier,
    onScan: () -> Unit = {},
    onOpenDocument: (String) -> Unit = {},
    onOpenRecent: ((PdfKey) -> Unit)? = null,
    onOpenPdfAtPage: ((PdfKey, Int) -> Unit)? = null,
    onOpenDraft: (String) -> Unit = {},
    focusSearch: Boolean = false,
    selectDocumentId: String? = null,
    onCancelPick: (() -> Unit)? = null,
) {
    val model: LibraryViewModel = hiltViewModel()
    val state by model.uiState.collectAsStateWithLifecycle()
    if (onCancelPick != null) {
        ChooseScanScreen(state, model, onOpenDocument, onCancelPick, modifier)
        return
    }
    val unsavedScan by model.unsavedScan.collectAsStateWithLifecycle()
    val recent by model.continueReading.collectAsStateWithLifecycle()
    val filter by model.filter.collectAsStateWithLifecycle()
    val selection by model.selection.collectAsStateWithLifecycle()
    val listLayout by model.listLayout.collectAsStateWithLifecycle()
    val folders by model.folderList.collectAsStateWithLifecycle()
    val deleted by model.deleted.collectAsStateWithLifecycle()
    val tags by model.tags.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var manageTags by rememberSaveable { mutableStateOf(false) }
    var moveDocumentId by rememberSaveable { mutableStateOf<String?>(null) }
    var editTagsId by rememberSaveable { mutableStateOf<String?>(null) }
    var folderMode by rememberSaveable { mutableStateOf(false) }
    var bin by rememberSaveable { mutableStateOf(false) }
    var move by rememberSaveable { mutableStateOf(false) }
    var create by rememberSaveable { mutableStateOf(false) }
    var discardSessionId by rememberSaveable { mutableStateOf<String?>(null) }
    val currentFolder = folders.firstOrNull { it.folder.id == filter.folderId }?.folder
    val childFolders = folders.filter { it.folder.parentId == filter.folderId }
    val folderGrid = folderMode && filter.folderId == null
    val canCreateFolder = folderGrid || (currentFolder != null && currentFolder.parentId == null)
    val selecting = selection.isNotEmpty()
    val visibleScan = unsavedScan.takeIf { filter.query.isBlank() && filter.tagId == null && !selecting && !bin }
    val snackbar = remember { SnackbarHostState() }
    LibraryNotices(model, snackbar)

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

    val searchFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var searchRequested by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(focusSearch) {
        if (focusSearch && !searchRequested) {
            searchFocus.requestFocus()
            keyboard?.show()
            searchRequested = true
        }
    }
    var selectionRequested by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(selectDocumentId, state) {
        if (!selectionRequested && selectDocumentId != null && !state.isLoading) {
            state.documents.firstOrNull { it.id == selectDocumentId }?.let(model::toggleSelection)
            selectionRequested = true
        }
    }
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
                                style = MaterialTheme.typography.headlineMedium,
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
                                    text = { Text(stringResource(R.string.library_manage_tags)) },
                                    onClick = { it(); manageTags = true },
                                )
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
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).focusRequester(searchFocus),
                                shape = MaterialTheme.shapes.medium,
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
                                model.updateFilter { it.copy(favouritesOnly = false, query = "", tagId = null) }
                            }, label = { Text(stringResource(R.string.library_folders)) })
                        }
                    }
                    if (tags.isNotEmpty()) {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(tags, key = { it.tag.id }) { summary ->
                                FilterChip(
                                    selected = !folderGrid && filter.tagId == summary.tag.id,
                                    onClick = {
                                        if (folderGrid) folderMode = false
                                        model.toggleTag(summary.tag.id)
                                    },
                                    label = { Text(summary.tag.name) },
                                    leadingIcon = { TagDot(summary.tag.colorIndex) },
                                )
                            }
                        }
                    }
                }
            }
        },
        floatingActionButton = {
            if (!bin && !selecting) {
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (canCreateFolder) {
                        ExtendedFloatingActionButton(onClick = { create = true }) {
                            Text(stringResource(R.string.library_new_folder))
                        }
                    }
                    ScanButton(onScan)
                }
            }
        },
    ) { padding ->
        val content = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
        val showRecent = onOpenRecent != null && recent.isNotEmpty() && !folderMode && !selecting &&
            filter.folderId == null && filter.query.isBlank() && !filter.favouritesOnly && filter.tagId == null
        val visibleFolders = if (folderMode && filter.query.isBlank() && filter.tagId == null) childFolders else emptyList()
        val showContentSearch = !bin && !folderGrid && !selecting && filter.tagId == null && filter.query.trim().length >= 2
        val hits = if (showContentSearch) insideDocumentHits(filter.query) else emptyList()
        when {
            bin -> RecycleBin(content, deleted, model::restore, model::deleteForever)
            visibleScan == null && !folderGrid && (state.isLoading || state.filter.folderId != filter.folderId || state.filter.tagId != filter.tagId) -> Box(content, contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            visibleScan == null && !showContentSearch && !showRecent && visibleFolders.isEmpty() &&
                (folderGrid || state.documents.isEmpty()) -> {
                if (!folderMode && filter.query.isBlank() && !filter.favouritesOnly && filter.tagId == null) EmptyLibrary(content)
                else Box(content, contentAlignment = Alignment.Center) {
                    val label = when {
                        folderGrid -> R.string.library_no_folders
                        filter.tagId != null -> R.string.library_empty_tag
                        else -> R.string.no_documents
                    }
                    Text(stringResource(label))
                }
            }
            else -> key(folderMode, filter.folderId, listLayout) {
                LazyVerticalGrid(
                    columns = if (!folderGrid && listLayout) GridCells.Fixed(1) else GridCells.Adaptive(160.dp),
                    modifier = content,
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        top = 8.dp,
                        end = 16.dp,
                        bottom = if (canCreateFolder) 160.dp else 100.dp,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (visibleScan != null) {
                        item(key = "unsaved-scan", span = { GridItemSpan(maxLineSpan) }) {
                            UnsavedScanCard(
                                visibleScan,
                                model.thumbnailFile,
                                onContinue = { onOpenDraft(visibleScan.draftId) },
                                onDiscard = { discardSessionId = visibleScan.sessionId },
                            )
                        }
                    }
                    if (showRecent) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            ContinueReadingRow(recent, onOpenRecent)
                        }
                    }
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
                    if (!folderGrid && state.filter.folderId == filter.folderId) items(state.documents, key = DocumentSummary::id) { document ->
                        val share = {
                            model.share(document.id) { files, mime ->
                                context.startActivity(ShareIntents.forFiles(context, files, mime))
                            }
                        }
                        val delete = { model.deleteDocument(document.id) }
                        SwipeDocumentCard(enabled = !selecting, onShare = share, onDelete = delete) {
                            DocumentCard(
                                document, listLayout, document.id in selection, selecting,
                                onClick = {
                                    if (selecting) model.toggleSelection(document) else onOpenDocument(document.id)
                                },
                                onSelect = { model.toggleSelection(document) },
                                onMove = { moveDocumentId = document.id },
                                onTags = { editTagsId = document.id },
                                onShare = share,
                                onDelete = delete,
                            )
                        }
                    }
                    if (showContentSearch) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Text(stringResource(R.string.inside_documents), style = MaterialTheme.typography.titleMedium)
                        }
                        if (hits.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                            Text(stringResource(R.string.inside_documents_empty))
                        }
                        items(hits, key = { "text-${it.pdfKey.value}-${it.pageIndex}" }, span = { GridItemSpan(maxLineSpan) }) { hit ->
                            InsideDocumentHit(hit, onOpenPdfAtPage?.let { open -> { open(hit.pdfKey, hit.pageIndex) } })
                        }
                    }
                }
            }
        }
    }

    if (manageTags) ManageTagsDialog(tags, { manageTags = false }, model::renameTag, model::deleteTag)
    LibraryDocumentDialogs(
        model, folders, tags, visibleScan, editTagsId, moveDocumentId, discardSessionId,
        onCloseTags = { editTagsId = null },
        onCloseMove = { moveDocumentId = null },
        onCloseDiscard = { discardSessionId = null },
    )
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChooseScanScreen(
    state: LibraryUiState,
    model: LibraryViewModel,
    onChoose: (String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier,
) {
    val filter by model.filter.collectAsStateWithLifecycle()
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.choose_scan)) },
                navigationIcon = { LibraryBackButton(onCancel) },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = filter.query,
                onValueChange = { query -> model.updateFilter { it.copy(query = query) } },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                label = { Text(stringResource(R.string.library_search)) },
                singleLine = true,
            )
            when {
                state.isLoading -> CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally).padding(16.dp))
                state.filter == filter && state.documents.isEmpty() -> Text(
                    stringResource(if (filter.query.isBlank()) R.string.no_documents else R.string.library_no_search_results),
                    modifier = Modifier.padding(16.dp),
                )
            }
            LazyColumn(Modifier.fillMaxWidth()) {
                items(state.documents, key = DocumentSummary::id) { document ->
                    ListItem(
                        headlineContent = { Text(document.title) },
                        supportingContent = {
                            Text(pluralStringResource(R.plurals.page_count, document.pageCount, document.pageCount))
                        },
                        modifier = Modifier.clickable { onChoose(document.id) },
                    )
                }
            }
        }
    }
}

@Composable
internal fun UnsavedScanCard(
    scan: UnsavedScan,
    thumbnailFile: (String) -> File,
    onContinue: () -> Unit,
    onDiscard: () -> Unit,
) {
    val count = pluralStringResource(R.plurals.page_count, scan.pageCount, scan.pageCount)
    val time = DateUtils.getRelativeTimeSpanString(
        scan.startedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS,
    )
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.library_unsaved_scan, count, time),
                style = MaterialTheme.typography.titleSmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (pageId in scan.thumbnailPageIds) {
                    AsyncImage(
                        model = thumbnailFile(pageId),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(width = 48.dp, height = 64.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onContinue) { Text(stringResource(R.string.library_continue_scan)) }
                TextButton(onClick = onDiscard) { Text(stringResource(R.string.library_discard_scan)) }
            }
        }
    }
}

@Composable
internal fun LibraryBackHandler(enabled: Boolean, onBack: () -> Unit) {
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
    GlyphButton(R.string.detail_back_icon, stringResource(R.string.detail_back), onClick = onClick)
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
private fun ContinueReadingRow(recent: List<ContinueReadingItem>, onOpen: (PdfKey) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.library_continue_reading), style = MaterialTheme.typography.titleMedium)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(recent, key = { it.pdfKey.value }) { pdf ->
                Card(onClick = { onOpen(pdf.pdfKey) }, modifier = Modifier.width(176.dp)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(pdf.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            stringResource(R.string.library_reading_page, pdf.page, pdf.pageCount),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        LinearProgressIndicator(progress = { pdf.progress }, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
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
internal fun DocumentCard(
    document: DocumentSummary,
    listLayout: Boolean,
    selected: Boolean,
    selecting: Boolean,
    onClick: () -> Unit,
    onSelect: () -> Unit,
    onMove: () -> Unit,
    onTags: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    activityAt: Long = document.updatedAt,
) {
    val cover = remember(document.coverThumbPath) { document.coverThumbPath?.let(::File) }
    val relativeDate = DateUtils.getRelativeTimeSpanString(
        activityAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS,
    )
    val count = pluralStringResource(R.plurals.page_count, document.pageCount, document.pageCount)
    val cardColor = if (selected) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.surfaceContainerHighest
    var menu by remember { mutableStateOf(false) }
    val longClickLabel = stringResource(if (selecting) R.string.library_select_document else R.string.detail_more)

    Card(
        modifier = Modifier.fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClickLabel = longClickLabel, onLongClick = {
                if (selecting) onSelect() else menu = true
            })
            .semantics { this.selected = selected },
        colors = CardDefaults.cardColors(containerColor = cardColor),
    ) {
        val image: @Composable (Modifier) -> Unit = { modifier ->
            AsyncImage(
                model = cover, contentDescription = null, contentScale = ContentScale.Crop,
                // No shared element here: the cover loads asynchronously, so going back before it was cached flew an
                // empty grey square into the list.
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
                DocumentTagChips(document.tags)
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
        DropdownMenu(menu && !selecting, { menu = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.library_move_to_folder)) },
                onClick = { menu = false; onMove() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.library_tags)) },
                onClick = { menu = false; onTags() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.detail_share)) },
                onClick = { menu = false; onShare() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.detail_delete)) },
                onClick = { menu = false; onDelete() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.library_select_document)) },
                onClick = { menu = false; onSelect() },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ScanButton(onScan: () -> Unit) {
    MediumExtendedFloatingActionButton(
        onClick = onScan,
        icon = { Icon(painterResource(DesignR.drawable.ic_scan), contentDescription = null) },
        text = { Text(stringResource(R.string.scan)) },
    )
}

@Composable
internal fun LibraryNotices(model: LibraryViewModel, snackbar: SnackbarHostState) {
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
}

@Composable
internal fun LibraryDocumentDialogs(
    model: LibraryViewModel,
    folders: List<FolderSummary>,
    tags: List<TagSummary>,
    unsavedScan: UnsavedScan?,
    editTagsId: String?,
    moveDocumentId: String?,
    discardSessionId: String?,
    onCloseTags: () -> Unit,
    onCloseMove: () -> Unit,
    onCloseDiscard: () -> Unit,
) {
    editTagsId?.let { id ->
        val assigned = remember(model, id) { model.documentTags(id) }
        EditTagsDialog(assigned, tags, onDismiss = onCloseTags) { ids, name ->
            model.setTags(id, ids, name)
            onCloseTags()
        }
    }

    if (discardSessionId != null && discardSessionId == unsavedScan?.sessionId) {
        AlertDialog(
            onDismissRequest = onCloseDiscard,
            title = { Text(stringResource(R.string.library_discard_scan_title)) },
            text = { Text(stringResource(R.string.library_discard_scan_message)) },
            confirmButton = {
                TextButton(onClick = {
                    model.discardScan(discardSessionId)
                    onCloseDiscard()
                }) { Text(stringResource(R.string.library_discard_scan)) }
            },
            dismissButton = {
                TextButton(onClick = onCloseDiscard) {
                    Text(stringResource(R.string.detail_cancel))
                }
            },
        )
    }
    moveDocumentId?.let { id ->
        MoveToFolderDialog(
            folders, onDismiss = onCloseMove,
            onSelect = { onCloseMove(); model.moveDocuments(setOf(id), it) },
            onCreate = { onCloseMove(); model.moveDocumentsToNewFolder(setOf(id), it) },
        )
    }
}
