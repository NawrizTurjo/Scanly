package dev.turjo.paperless

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import dev.turjo.paperless.core.designsystem.R as DesignR
import dev.turjo.paperless.core.model.PdfKey
import dev.turjo.paperless.feature.camera.CameraScreen
import dev.turjo.paperless.feature.editor.PageEditorScreen
import dev.turjo.paperless.feature.editor.PageViewerScreen
import dev.turjo.paperless.feature.editor.ReviewScreen
import dev.turjo.paperless.feature.export.ExportSheet
import dev.turjo.paperless.feature.library.DocumentScreen
import dev.turjo.paperless.feature.library.LibraryScreen
import dev.turjo.paperless.feature.reader.ReaderScreen
import dev.turjo.paperless.feature.settings.SettingsScreen
import dev.turjo.paperless.feature.tools.ToolsScreen
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable sealed interface Route : NavKey

@Serializable data object LibraryRoute : Route
@Serializable data object ReaderRoute : Route
@Serializable data object ToolsRoute : Route
@Serializable data object SettingsRoute : Route
@Serializable data class CameraRoute(
    val targetDocumentId: String? = null,
    val insertAt: Int? = null,
    val single: Boolean = false,
    val resumeDraftId: String? = null,
) : Route
@Serializable data class ReviewRoute(val draftDocumentId: String) : Route
@Serializable data class PageViewerRoute(val documentId: String, val startPageId: String) : Route
@Serializable data class PageEditorRoute(val pageId: String) : Route
@Serializable data class DocumentRoute(val documentId: String) : Route

internal data class PendingPdf(val key: PdfKey, val title: String? = null)

internal fun List<PendingPdf>.consumePdf(key: PdfKey?): List<PendingPdf> =
    if (key != null && firstOrNull()?.key == key) drop(1) else this

private val pendingPdfsSaver = listSaver<List<PendingPdf>, String>(
    save = { requests -> requests.flatMap { listOf(it.key.value, it.title.orEmpty()) } },
    restore = { saved -> saved.chunked(2).map { PendingPdf(PdfKey(it[0]), it[1].ifEmpty { null }) } },
)

private enum class Tab(val route: Route, val label: Int, val icon: Int) {
    LIBRARY(LibraryRoute, R.string.tab_library, DesignR.drawable.ic_library),
    READER(ReaderRoute, R.string.tab_reader, DesignR.drawable.ic_reader),
    TOOLS(ToolsRoute, R.string.tab_tools, DesignR.drawable.ic_tools),
    SETTINGS(SettingsRoute, R.string.tab_settings, DesignR.drawable.ic_settings),
}

private fun NavBackStack<NavKey>.replaceAll(vararg keys: Route) {
    clear()
    addAll(keys)
}

/** Back from the camera's "review" goes to an existing Review for the same draft instead of stacking another. */
private fun NavBackStack<NavKey>.showReview(draftId: String) {
    val existing = indexOfLast { it is ReviewRoute && it.draftDocumentId == draftId }
    if (existing >= 0) {
        while (size > existing + 1) removeAt(lastIndex)
    } else {
        if (lastOrNull() is CameraRoute) removeAt(lastIndex)
        add(ReviewRoute(draftId))
    }
}

@Composable
internal fun ScanlyNavigation(
    initialStack: List<Route>,
    entries: Flow<EntryEvent>,
    onDocumentOpened: (String) -> Unit,
    onOpenDocumentInReader: (String) -> Unit,
    onOpenPdf: (PdfKey) -> Unit,
    preparingPdf: Boolean,
    onOpenFile: (java.io.File) -> Unit = {},
    onOpenPdfAtPage: (PdfKey, Int) -> Unit = { key, _ -> onOpenPdf(key) },
    onAdjustImages: ((List<android.net.Uri>) -> Unit)? = null,
) {
    val backStack = rememberNavBackStack(*initialStack.toTypedArray())
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val resources = LocalResources.current
    var pendingPdfs by rememberSaveable(stateSaver = pendingPdfsSaver) {
        mutableStateOf(emptyList<PendingPdf>())
    }
    val pendingPdfKey = pendingPdfs.firstOrNull()?.key
    val pendingPdfTitle = pendingPdfs.firstOrNull()?.title
    LaunchedEffect(entries, resources) {
        entries.collect { event ->
            when (val route = event.route) {
                is ReviewRoute -> {
                    if (event.previousScanSaved) backStack.replaceAll(LibraryRoute, route)
                    else backStack.showReview(route.draftDocumentId)
                }
                null -> Unit
                else -> backStack.replaceAll(LibraryRoute, route)
            }
            event.pdfKey?.let { key ->
                if (pendingPdfs.none { it.key == key }) pendingPdfs += PendingPdf(key, event.pdfTitle)
                if (backStack.size != 1 || backStack.lastOrNull() != ReaderRoute) {
                    backStack.replaceAll(ReaderRoute)
                }
            }
            scope.launch {
                if (event.previousScanSaved) {
                    snackbar.showSnackbar(resources.getString(R.string.share_previous_scan_saved))
                }
                event.message?.let { snackbar.showSnackbar(resources.getString(it)) }
            }
        }
    }
    var exportDocumentId by rememberSaveable { mutableStateOf<String?>(null) }
    val top = backStack.lastOrNull()
    val currentTab = Tab.entries.firstOrNull { it.route == top }
    var readerImmersive by remember { mutableStateOf(false) }
    val immersive = top == ReaderRoute && readerImmersive

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (currentTab != null && !immersive) {
                NavigationBar {
                    for (tab in Tab.entries) {
                        NavigationBarItem(
                            selected = tab == currentTab,
                            onClick = { if (tab != currentTab) backStack.replaceAll(tab.route) },
                            icon = { Icon(painterResource(tab.icon), contentDescription = null) },
                            label = { Text(stringResource(tab.label)) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        // Only tab roots sit above the bottom bar; full-screen routes (camera, editor) handle their own insets.
        val contentModifier = if (currentTab != null && !immersive) {
            Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
        } else {
            Modifier.fillMaxSize()
        }
        NavDisplay(
            backStack = backStack,
            onBack = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) },
            modifier = contentModifier,
            entryDecorators = listOf(
                rememberSaveableStateHolderNavEntryDecorator(),
                rememberViewModelStoreNavEntryDecorator(),
            ),
            entryProvider = entryProvider {
                entry<LibraryRoute> {
                    LibraryScreen(
                        onScan = { backStack.add(CameraRoute()) },
                        onOpenDocument = { backStack.add(DocumentRoute(it)) },
                        onOpenDraft = { backStack.showReview(it) },
                        onOpenRecent = onOpenPdf,
                        onOpenPdfAtPage = onOpenPdfAtPage,
                    )
                }
                entry<ReaderRoute> {
                    ReaderScreen(
                        openRequest = pendingPdfKey,
                        onOpenRequestConsumed = { pendingPdfs = pendingPdfs.consumePdf(pendingPdfKey) },
                        openRequestTitle = pendingPdfTitle,
                        active = top == ReaderRoute,
                        onImmersiveChanged = { readerImmersive = it },
                    )
                }
                entry<ToolsRoute> { ToolsScreen(onOpenPdf = onOpenFile, onAdjustImages = onAdjustImages) }
                entry<SettingsRoute> { SettingsScreen() }
                entry<CameraRoute> { key ->
                    CameraScreen(
                        targetDocumentId = key.targetDocumentId,
                        insertAt = key.insertAt,
                        singleMode = key.single,
                        resumeDraftId = key.resumeDraftId,
                        onReview = { draftId -> backStack.showReview(draftId) },
                        onClose = { backStack.removeAt(backStack.lastIndex) },
                    )
                }
                entry<ReviewRoute> { key ->
                    ReviewScreen(
                        draftDocumentId = key.draftDocumentId,
                        onViewPage = { backStack.add(PageViewerRoute(key.draftDocumentId, it)) },
                        onAddMore = { backStack.add(CameraRoute(resumeDraftId = key.draftDocumentId)) },
                        onSaved = { documentId -> backStack.replaceAll(LibraryRoute, DocumentRoute(documentId)) },
                        onBack = { backStack.removeAt(backStack.lastIndex) },
                    )
                }
                entry<PageViewerRoute> { key ->
                    PageViewerScreen(
                        documentId = key.documentId,
                        startPageId = key.startPageId,
                        onBack = { if (backStack.lastOrNull() == key) backStack.removeAt(backStack.lastIndex) },
                        onEdit = { if (backStack.lastOrNull() == key) backStack.add(PageEditorRoute(it)) },
                    )
                }
                entry<PageEditorRoute> { key ->
                    PageEditorScreen(pageId = key.pageId, onBack = { backStack.removeAt(backStack.lastIndex) })
                }
                entry<DocumentRoute> { key ->
                    LaunchedEffect(key.documentId) { onDocumentOpened(key.documentId) }
                    DocumentScreen(
                        documentId = key.documentId,
                        onBack = { backStack.removeAt(backStack.lastIndex) },
                        onViewPage = { backStack.add(PageViewerRoute(key.documentId, it)) },
                        onAddPages = { id, insertAt -> backStack.add(CameraRoute(targetDocumentId = id, insertAt = insertAt)) },
                        onExport = { exportDocumentId = it },
                        onOpenInReader = onOpenDocumentInReader,
                    )
                }
            },
        )
    }
    if (preparingPdf) {
        Surface(Modifier.fillMaxWidth().safeDrawingPadding(), tonalElevation = 4.dp) {
            Row(
                Modifier.padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(Modifier.size(20.dp))
                Text(stringResource(R.string.pdf_preparing))
            }
        }
    }
    exportDocumentId?.let { id -> ExportSheet(documentId = id, onDismiss = { exportDocumentId = null }) }
}
