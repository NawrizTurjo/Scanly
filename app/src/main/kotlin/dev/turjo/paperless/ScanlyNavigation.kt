package dev.turjo.paperless

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ShortNavigationBarDefaults
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
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.navigation3.runtime.NavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import androidx.navigation3.ui.NavDisplay
import dev.turjo.paperless.core.designsystem.LocalNavAnimatedScope
import dev.turjo.paperless.core.designsystem.LocalSharedTransitionScope
import dev.turjo.paperless.core.designsystem.R as DesignR
import dev.turjo.paperless.core.model.PdfKey
import dev.turjo.paperless.feature.camera.CameraScreen
import dev.turjo.paperless.feature.editor.PageEditorScreen
import dev.turjo.paperless.feature.editor.PageViewerScreen
import dev.turjo.paperless.feature.editor.ReviewScreen
import dev.turjo.paperless.feature.export.ExportSheet
import dev.turjo.paperless.feature.library.DocumentScreen
import dev.turjo.paperless.feature.library.LibraryScreen
import dev.turjo.paperless.feature.library.HomeScreen
import dev.turjo.paperless.feature.tools.HomeToolOutputs
import dev.turjo.paperless.feature.reader.ReaderScreen
import dev.turjo.paperless.feature.settings.SettingsScreen
import dev.turjo.paperless.feature.tools.ToolsScreen
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable sealed interface Route : NavKey

@Serializable data object HomeRoute : Route
@Serializable data class FilesEntryRoute(
    val focusSearch: Boolean = false,
    val selectDocumentId: String? = null,
) : Route
@Serializable data class ToolRoute(val toolId: String) : Route
@Serializable data object LibraryRoute : Route
@Serializable data object ReaderRoute : Route
@Serializable data object ToolsRoute : Route
@Serializable data object SettingsRoute : Route
@Serializable data class CameraRoute(
    val targetDocumentId: String? = null,
    val insertAt: Int? = null,
    val single: Boolean = false,
    val resumeDraftId: String? = null,
    val startMode: String? = null,
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

private val readerBottomBarHeight = 64.dp

private enum class Tab(val route: Route, val label: Int, val icon: Int) {
    HOME(HomeRoute, R.string.tab_home, R.drawable.ic_home),
    LIBRARY(LibraryRoute, R.string.tab_library, DesignR.drawable.ic_library),
    READER(ReaderRoute, R.string.tab_reader, DesignR.drawable.ic_reader),
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

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
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
    onAdjustImages: ((List<Uri>) -> Unit)? = null,
    onPickPdf: (Uri) -> Unit = {},
) {
    val backStack = rememberNavBackStack(*initialStack.toTypedArray())
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { uris ->
        if (uris.isNotEmpty()) onAdjustImages?.invoke(uris)
    }
    val pdfPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(onPickPdf)
    }
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
                    if (event.previousScanSaved) {
                        val root = if (backStack.lastOrNull() == HomeRoute) HomeRoute else LibraryRoute
                        backStack.replaceAll(root, route)
                    } else {
                        backStack.showReview(route.draftDocumentId)
                    }
                }
                ToolsRoute -> backStack.replaceAll(HomeRoute, ToolsRoute)
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
    val currentTab = if (top is FilesEntryRoute) Tab.LIBRARY else Tab.entries.firstOrNull { it.route == top }
    var readerImmersive by remember { mutableStateOf(false) }
    val readerActive = top == ReaderRoute

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (currentTab != null) {
                val tabBar: @Composable (WindowInsets) -> Unit = { insets ->
                    ShortNavigationBar(windowInsets = insets) {
                        for (tab in Tab.entries) {
                            ShortNavigationBarItem(
                                selected = tab == currentTab,
                                onClick = { if (tab != currentTab) backStack.replaceAll(tab.route) },
                                icon = { Icon(painterResource(tab.icon), contentDescription = null) },
                                label = { Text(stringResource(tab.label)) },
                            )
                        }
                    }
                }
                if (readerActive) {
                    AnimatedVisibility(
                        visible = !readerImmersive,
                        enter = slideInVertically(tween(220, easing = FastOutSlowInEasing)) { it } +
                            fadeIn(tween(220, easing = FastOutSlowInEasing)),
                        exit = slideOutVertically(tween(220, easing = FastOutSlowInEasing)) { it } +
                            fadeOut(tween(220, easing = FastOutSlowInEasing)),
                    ) {
                        tabBar(WindowInsets.systemBarsIgnoringVisibility.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                    }
                } else {
                    tabBar(ShortNavigationBarDefaults.windowInsets)
                }
            }
        },
    ) { padding ->
        // Only tab roots sit above the bottom bar; full-screen routes (camera, editor) handle their own insets.
        val contentModifier = if (currentTab != null && !readerActive) {
            Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
        } else {
            Modifier.fillMaxSize()
        }
        val pageMotionDecorator = remember {
            NavEntryDecorator<NavKey> { entry ->
                CompositionLocalProvider(LocalNavAnimatedScope provides LocalNavAnimatedContentScope.current) {
                    entry.Content()
                }
            }
        }
        SharedTransitionLayout(modifier = contentModifier) {
            CompositionLocalProvider(LocalSharedTransitionScope provides this@SharedTransitionLayout) {
                NavDisplay(
                    backStack = backStack,
                    onBack = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) },
                    modifier = Modifier.fillMaxSize(),
                    sharedTransitionScope = this,
                    entryDecorators = listOf(
                        rememberSaveableStateHolderNavEntryDecorator(),
                        rememberViewModelStoreNavEntryDecorator(),
                        pageMotionDecorator,
                    ),
                    entryProvider = entryProvider {
                        entry<HomeRoute> {
                            HomeScreen(
                                shortcuts = homeShortcuts,
                                onShortcut = { id ->
                                    when (id) {
                                        "import_images" -> imagePicker.launch(
                                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                                        )
                                        "open_pdf" -> pdfPicker.launch(arrayOf(PDF_MIME_TYPE))
                                        else -> homeShortcutRoute(id)?.let(backStack::add)
                                    }
                                },
                                onSearch = { backStack.add(FilesEntryRoute(focusSearch = true)) },
                                onFiles = { backStack.add(LibraryRoute) },
                                onOpenDocument = { backStack.add(DocumentRoute(it)) },
                                onSelectDocument = { backStack.add(FilesEntryRoute(selectDocumentId = it)) },
                                onOpenDraft = { backStack.showReview(it) },
                                recentToolOutputs = { host -> HomeToolOutputs(onOpenFile, host) },
                            )
                        }
                        entry<FilesEntryRoute> { key ->
                            LibraryScreen(
                                onScan = { backStack.add(CameraRoute()) },
                                onOpenDocument = { backStack.add(DocumentRoute(it)) },
                                onOpenDraft = { backStack.showReview(it) },
                                onOpenRecent = onOpenPdf,
                                onOpenPdfAtPage = onOpenPdfAtPage,
                                focusSearch = key.focusSearch,
                                selectDocumentId = key.selectDocumentId,
                            )
                        }
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
                                active = readerActive,
                                bottomBarHeight = readerBottomBarHeight,
                                onImmersiveChanged = { readerImmersive = it },
                            )
                        }
                        entry<ToolsRoute> {
                            ToolsScreen(
                                onOpenPdf = onOpenFile, onAdjustImages = onAdjustImages,
                                onClose = { backStack.removeAt(backStack.lastIndex) },
                            )
                        }
                        entry<ToolRoute> { key ->
                            ToolsScreen(
                                onOpenPdf = onOpenFile, onAdjustImages = onAdjustImages, initialTool = key.toolId,
                                onClose = { backStack.removeAt(backStack.lastIndex) },
                            )
                        }
                        entry<SettingsRoute> { SettingsScreen() }
                        entry<CameraRoute> { key ->
                            CameraScreen(
                                targetDocumentId = key.targetDocumentId,
                                insertAt = key.insertAt,
                                singleMode = key.single,
                                resumeDraftId = key.resumeDraftId,
                                startMode = key.startMode,
                                onReview = { draftId -> backStack.showReview(draftId) },
                                onClose = { backStack.removeAt(backStack.lastIndex) },
                            )
                        }
                        entry<ReviewRoute> { key ->
                            ReviewScreen(
                                draftDocumentId = key.draftDocumentId,
                                onViewPage = { backStack.add(PageViewerRoute(key.draftDocumentId, it)) },
                                onAddMore = { backStack.add(CameraRoute(resumeDraftId = key.draftDocumentId)) },
                                onSaved = { documentId ->
                                    val root = if (backStack.firstOrNull() == HomeRoute) HomeRoute else LibraryRoute
                                    backStack.replaceAll(root, DocumentRoute(documentId))
                                },
                                onBack = { backStack.removeAt(backStack.lastIndex) },
                                onExport = { exportDocumentId = it },
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
        }
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
