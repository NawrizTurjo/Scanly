package dev.turjo.paperless.feature.library

import android.content.SharedPreferences
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.turjo.paperless.core.pdf.ShareIntents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import dev.turjo.paperless.core.designsystem.R as DesignR

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    shortcuts: List<HomeShortcut>,
    onShortcut: (String) -> Unit,
    onSearch: () -> Unit,
    onFiles: () -> Unit,
    onOpenDocument: (String) -> Unit,
    onSelectDocument: (String) -> Unit,
    onOpenDraft: (String) -> Unit,
    recentToolOutputs: @Composable (SnackbarHostState) -> Unit,
) {
    val model: LibraryViewModel = hiltViewModel()
    val context = LocalContext.current
    val appContext = context.applicationContext
    val documents by remember(model, appContext) { model.observeRecentDocuments(appContext) }
        .collectAsStateWithLifecycle(initialValue = null)
    val unsaved by model.unsavedScan.collectAsStateWithLifecycle()
    val folders by model.folderList.collectAsStateWithLifecycle()
    val tags by model.tags.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LibraryNotices(model, snackbar)

    val catalog = remember(shortcuts) {
        (shortcuts + HomeShortcut(ALL_TOOLS, R.string.home_all_tools, DesignR.drawable.ic_tools)).associateBy { it.id }
    }
    val saved by produceState<Pair<SharedPreferences, String?>?>(null, appContext) {
        value = withContext(Dispatchers.IO) {
            val preferences = appContext.homePreferences()
            preferences to preferences.getString(SHORTCUTS_KEY, null)
        }
    }
    val preferences = saved?.first
    var configured by remember(catalog, saved) {
        mutableStateOf(decodeShortcuts(saved?.second, catalog.keys))
    }
    var editing by rememberSaveable { mutableStateOf(false) }
    var moveId by rememberSaveable { mutableStateOf<String?>(null) }
    var tagsId by rememberSaveable { mutableStateOf<String?>(null) }
    var discardId by rememberSaveable { mutableStateOf<String?>(null) }
    val editShortcuts = { if (preferences != null) editing = true }
    val scan = { onShortcut("scan") }
    val listState = rememberLazyListState()
    // The unsaved-scan card loads after the rest of Home. A lazy list keeps its first visible item
    // in place when something appears above it, which left the card half hidden under the title.
    // If the user was at the top, show the card.
    val hasDraft = unsaved != null
    LaunchedEffect(hasDraft) {
        if (hasDraft && listState.firstVisibleItemIndex <= 1) listState.scrollToItem(0)
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.home_title), style = MaterialTheme.typography.headlineMedium) },
                actions = {
                    IconButton(onClick = onSearch) {
                        Icon(painterResource(R.drawable.home_search), stringResource(R.string.library_search))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = { ScanButton(scan) },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
            state = listState,
            contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 100.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            unsaved?.let { draft ->
                item("draft") {
                    UnsavedScanCard(
                        draft, model.thumbnailFile, { onOpenDraft(draft.draftId) },
                        { discardId = draft.sessionId },
                    )
                }
            }
            if (documents?.isEmpty() == true && unsaved == null) {
                item("first-scan") {
                    Card(
                        onClick = scan,
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    ) {
                        Row(
                            Modifier.padding(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(painterResource(DesignR.drawable.ic_scan), contentDescription = null)
                            Text(stringResource(R.string.home_first_scan), style = MaterialTheme.typography.titleLarge)
                        }
                    }
                }
            }
            item("shortcuts-heading") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.home_shortcuts),
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    TextButton(onClick = editShortcuts, enabled = preferences != null) {
                        Text(stringResource(R.string.home_edit))
                    }
                }
            }
            items(configured.chunked(3), key = { it.first() }) { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (id in row) {
                        val shortcut = catalog.getValue(id)
                        ShortcutTile(
                            shortcut, Modifier.weight(1f), { onShortcut(id) },
                            editShortcuts.takeIf { preferences != null },
                        )
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            val recent = documents.orEmpty()
            if (recent.isNotEmpty()) {
                item("recent-heading") {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.home_recent),
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.titleLarge,
                        )
                        TextButton(onClick = onFiles) { Text(stringResource(R.string.home_all_files)) }
                    }
                }
                items(recent, key = { "recent:${it.document.id}" }) { entry ->
                    val document = entry.document
                    DocumentCard(
                        document = document,
                        listLayout = true,
                        selected = false,
                        selecting = false,
                        onClick = { onOpenDocument(document.id) },
                        onSelect = { onSelectDocument(document.id) },
                        onMove = { moveId = document.id },
                        onTags = { tagsId = document.id },
                        onShare = {
                            model.share(document.id) { files, mime ->
                                context.startActivity(ShareIntents.forFiles(context, files, mime))
                            }
                        },
                        onDelete = { model.deleteDocument(document.id) },
                        activityAt = entry.activityAt,
                    )
                }
            }
            item("tool-outputs") { recentToolOutputs(snackbar) }
        }
    }
    preferences?.let { store ->
        if (editing) ShortcutEditor(configured, catalog, onDismiss = { editing = false }) { changed ->
            store.edit().putString(SHORTCUTS_KEY, encodeShortcuts(changed)).apply()
            configured = changed
            editing = false
        }
    }
    LibraryDocumentDialogs(
        model, folders, tags, unsaved, tagsId, moveId, discardId,
        onCloseTags = { tagsId = null },
        onCloseMove = { moveId = null },
        onCloseDiscard = { discardId = null },
    )
}

@Composable
private fun ShortcutTile(
    shortcut: HomeShortcut,
    modifier: Modifier,
    onClick: () -> Unit,
    onEdit: (() -> Unit)?,
) {
    val colors = MaterialTheme.colorScheme
    val (container, content) = when {
        shortcut.id == "scan" -> colors.primaryContainer to colors.onPrimaryContainer
        shortcut.id.startsWith("tool:") -> colors.tertiaryContainer to colors.onTertiaryContainer
        shortcut.id == ALL_TOOLS -> colors.surfaceContainerHigh to colors.onSurface
        else -> colors.secondaryContainer to colors.onSecondaryContainer
    }
    Surface(modifier, shape = MaterialTheme.shapes.medium, color = container, contentColor = content) {
        Column(
            Modifier.fillMaxWidth()
                .heightIn(min = 92.dp)
                .combinedClickable(
                    role = Role.Button,
                    onClick = onClick,
                    onLongClick = onEdit,
                    onLongClickLabel = stringResource(R.string.home_edit_shortcuts),
                )
                .padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
        ) {
            Icon(painterResource(shortcut.icon), contentDescription = null)
            Text(
                stringResource(shortcut.title),
                style = MaterialTheme.typography.labelLarge,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShortcutEditor(
    configured: List<String>,
    catalog: Map<String, HomeShortcut>,
    onDismiss: () -> Unit,
    onSave: (List<String>) -> Unit,
) {
    var order by rememberSaveable { mutableStateOf(ArrayList(configured.filter { it != ALL_TOOLS })) }
    ModalBottomSheet(onDismissRequest = onDismiss, shape = MaterialTheme.shapes.extraLarge) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.home_edit_shortcuts), Modifier.weight(1f),
                style = MaterialTheme.typography.titleLarge,
            )
            TextButton(onClick = { onSave(order + ALL_TOOLS) }) { Text(stringResource(R.string.home_save)) }
        }
        LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 16.dp)) {
            items(order + catalog.keys.filter { it !in order && it != ALL_TOOLS }, key = { it }) { id ->
                val index = order.indexOf(id)
                val title = stringResource(catalog.getValue(id).title)
                ListItem(
                    leadingContent = {
                        Checkbox(
                            checked = index >= 0,
                            onCheckedChange = { checked -> order = ArrayList(if (checked) order + id else order - id) },
                            modifier = Modifier.semantics { contentDescription = title },
                        )
                    },
                    trailingContent = if (index < 0) null else {
                        {
                            Row {
                                IconButton(
                                    onClick = { order = order.moved(index, index - 1) },
                                    enabled = index > 0,
                                ) {
                                    Icon(
                                        painterResource(R.drawable.home_arrow_up),
                                        stringResource(R.string.home_move_up, title),
                                    )
                                }
                                IconButton(
                                    onClick = { order = order.moved(index, index + 1) },
                                    enabled = index < order.lastIndex,
                                ) {
                                    Icon(
                                        painterResource(R.drawable.home_arrow_down),
                                        stringResource(R.string.home_move_down, title),
                                    )
                                }
                            }
                        }
                    },
                ) { Text(title) }
            }
            item(ALL_TOOLS) {
                ListItem(supportingContent = { Text(stringResource(R.string.home_always_last)) }) {
                    Text(stringResource(R.string.home_all_tools))
                }
            }
        }
    }
}

private fun List<String>.moved(from: Int, to: Int) = ArrayList(this).apply { add(to, removeAt(from)) }

