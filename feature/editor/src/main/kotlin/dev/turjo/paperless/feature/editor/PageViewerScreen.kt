package dev.turjo.paperless.feature.editor

import dev.turjo.paperless.core.designsystem.glassOverlay
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.clickable
import dev.turjo.paperless.core.designsystem.ScanlyScaffold
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.size.Precision
import coil3.size.Size as CoilSize
import dev.turjo.paperless.core.data.isIdComposite
import dev.turjo.paperless.core.designsystem.FirstRunTip
import dev.turjo.paperless.core.designsystem.ScanlyTheme
import dev.turjo.paperless.core.designsystem.sharedPage
import dev.turjo.paperless.core.model.ThemeMode
import dev.turjo.paperless.core.pdf.ShareIntents
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageViewerScreen(
    documentId: String,
    startPageId: String,
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
    startWithText: Boolean = false,
) {
    val model = hiltViewModel<PageViewerViewModel, PageViewerViewModel.Factory> {
        it.create(documentId, startPageId)
    }
    val state by model.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.close) {
        if (state.close) onBack()
    }
    BackHandler(enabled = state.changing) { }
    var startTextPending by rememberSaveable { mutableStateOf(startWithText) }
    val ids = state.pages.map { it.page.id }

    ScanlyTheme(themeMode = ThemeMode.DARK, dynamicColor = false) {
        val snackbar = remember { SnackbarHostState() }
        EditorMessage(state.message, snackbar, model::clearMessage)
        val context = LocalContext.current
        LaunchedEffect(state.shareFile) {
            state.shareFile?.let { file ->
                context.startActivity(ShareIntents.forFiles(context, listOf(file), "image/jpeg"))
                model.shareLaunched()
            }
        }
        key(ids) {
            // When an ID disappears, start at the neighbour selected by the ViewModel.
            val pager = rememberPagerState(initialPage = state.selectedIndex) { ids.size }
            LaunchedEffect(pager) {
                snapshotFlow { pager.settledPage }.collect { index ->
                    ids.getOrNull(index)?.let(model::select)
                }
            }
            val current = state.pages.getOrNull(pager.currentPage)
            val canAct = current != null && !state.changing && !pager.isScrollInProgress
            var zoomed by remember(current?.page?.id) { mutableStateOf(false) }
            var confirmDelete by remember(current?.page?.id) { mutableStateOf(false) }
            var showText by rememberSaveable(current?.page?.id) { mutableStateOf(false) }
            LaunchedEffect(current?.page?.id, startTextPending) {
                if (current != null && startTextPending) {
                    showText = true
                    startTextPending = false
                }
            }
            if (showText && current != null) {
                PageTextSheet(current.page.id, model) { showText = false }
            }
            ScanlyScaffold(
                modifier = Modifier.background(MaterialTheme.colorScheme.background).safeDrawingPadding(),
                topBar = {
                    TopAppBar(
                        title = {
                            if (current != null) {
                                Text(stringResource(R.string.viewer_page_position, pager.currentPage + 1, ids.size))
                            }
                        },
                        navigationIcon = {
                            IconButton(onBack, enabled = !state.changing) {
                                Icon(painterResource(R.drawable.ic_viewer_back), stringResource(R.string.editor_back))
                            }
                        },
                        actions = {
                            if (current != null) {
                                IconButton({ model.exportPage(current.page.id, share = true) }, enabled = canAct) {
                                    Icon(painterResource(R.drawable.ic_viewer_share), stringResource(R.string.viewer_share))
                                }
                                IconButton({ model.exportPage(current.page.id, share = false) }, enabled = canAct) {
                                    Icon(painterResource(R.drawable.ic_viewer_save), stringResource(R.string.viewer_save_image))
                                }
                            }
                        },
                    )
                },
                bottomBarGlass = false,
                colourPools = false,
                bottomBar = {
                    if (current != null) {
                        // A floating toolbar: icon and label per action, glass when the glass look is on.
                        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
                            Row(
                                Modifier.glassOverlay(MaterialTheme.colorScheme.surfaceContainerHigh, cornerRadius = 28.dp)
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                ViewerAction(R.drawable.ic_viewer_edit, R.string.viewer_edit, canAct, emphasized = true) {
                                    onEdit(current.page.id)
                                }
                                ViewerAction(R.drawable.ic_recognized_text, R.string.viewer_text, canAct) { showText = true }
                                if (!current.page.isIdComposite) {
                                    ViewerAction(R.drawable.ic_viewer_rotate, R.string.viewer_rotate, canAct) {
                                        model.rotate(current.page.id)
                                    }
                                }
                                ViewerAction(R.drawable.ic_viewer_delete, R.string.editor_delete, canAct) { confirmDelete = true }
                            }
                        }
                    }
                },
                snackbarHost = { SnackbarHost(snackbar) },
            ) { padding ->
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    if (ids.isEmpty() && !state.close) EditorProgress()
                    FirstRunTip(
                        "viewer_save",
                        stringResource(R.string.viewer_tip_save),
                        visible = canAct && !confirmDelete && !state.close,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        alignment = Alignment.TopEnd,
                    )
                    HorizontalPager(
                        state = pager,
                        modifier = Modifier.fillMaxSize(),
                        userScrollEnabled = !zoomed && !state.changing,
                        key = { ids[it] },
                    ) { index ->
                        val item = state.pages[index]
                        val active = index == pager.currentPage
                        key(active) {
                            val imageModifier = if (active && !state.close) {
                                Modifier.sharedPage("page-${item.page.id}")
                            } else {
                                Modifier
                            }
                            ViewerImage(item, index + 1, ids.size, active, imageModifier) { if (active) zoomed = it }
                        }
                    }
                }
            }
            if (confirmDelete && current != null) {
                ConfirmDialog(
                    R.string.viewer_delete_title,
                    R.string.viewer_delete_body,
                    R.string.editor_delete,
                    onDismiss = { confirmDelete = false },
                    onConfirm = {
                        confirmDelete = false
                        if (canAct) model.delete(current.page.id)
                    },
                )
            }
        }
    }
}

@Composable
private fun ViewerImage(
    item: ViewerPage,
    number: Int,
    count: Int,
    active: Boolean,
    imageModifier: Modifier,
    onZoomed: (Boolean) -> Unit,
) {
    val page = item.page
    val original = remember(page.originalPath) { File(page.originalPath) }
    val processed by key(page.processedPath, page.processedHash, item.busy) {
        produceState<Pair<File, Long>?>(null) {
            if (!item.busy) {
                value = withContext(Dispatchers.IO) {
                    page.processedPath?.let(::File)?.takeIf(File::isFile)?.let { it to it.lastModified() }
                }
            }
        }
    }
    val context = LocalContext.current
    // Decode for the whole screen, not the first layout pass: the page opens with a shared-element
    // animation that starts at thumbnail size, and Coil would otherwise keep that small decode.
    val screen = remember(context) {
        val metrics = context.resources.displayMetrics
        CoilSize(metrics.widthPixels, metrics.heightPixels)
    }
    val request = remember(context, original, processed, page.processedHash, screen) {
        val rendered = processed
        ImageRequest.Builder(context).data(rendered?.first ?: original)
            .size(screen)
            .precision(Precision.INEXACT)
            .apply {
                if (rendered != null) {
                    val cacheKey = "${rendered.first.path}:${page.processedHash}:${rendered.second}:$screen"
                    memoryCacheKey(cacheKey)
                    diskCacheKey(cacheKey)
                }
            }.build()
    }
    var imageSize by remember(request) { mutableStateOf(Size.Zero) }
    var scale by remember { mutableStateOf(1f) }
    var translation by remember { mutableStateOf(Offset.Zero) }
    val reportZoom by rememberUpdatedState(onZoomed)

    fun transform(viewport: Size, pivot: Offset, zoom: Float, pan: Offset) {
        if (imageSize.width <= 0f || imageSize.height <= 0f) return
        val next = zoom.coerceIn(1f, 5f)
        val fit = minOf(viewport.width / imageSize.width, viewport.height / imageSize.height)
        val maxX = ((imageSize.width * fit * next - viewport.width) / 2).coerceAtLeast(0f)
        val maxY = ((imageSize.height * fit * next - viewport.height) / 2).coerceAtLeast(0f)
        val fromCenter = pivot - Offset(viewport.width / 2, viewport.height / 2)
        val target = fromCenter + (translation - fromCenter) * (next / scale) + pan
        translation = Offset(target.x.coerceIn(-maxX, maxX), target.y.coerceIn(-maxY, maxY))
        scale = next
        reportZoom(next > 1f)
    }

    LaunchedEffect(imageSize) {
        scale = 1f
        translation = Offset.Zero
        reportZoom(false)
    }

    val gestures = if (active) {
        Modifier.pointerInput(imageSize) {
            val viewport = Size(size.width.toFloat(), size.height.toFloat())
            detectTapGestures(onDoubleTap = { point ->
                transform(viewport, point, if (scale > 1f) 1f else 2.5f, Offset.Zero)
            })
        }.pointerInput(imageSize) {
            awaitEachGesture {
                // Let the pager handle single-finger swipes at 1x.
                var transforming = false
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val fingers = event.changes.count { it.pressed && it.previousPressed }
                    transforming = transforming || fingers > 1
                    if (fingers > 0 && (transforming || scale > 1f)) {
                        val pan = event.calculatePan()
                        val zoom = event.calculateZoom()
                        if (pan != Offset.Zero || zoom != 1f) {
                            transform(
                                Size(size.width.toFloat(), size.height.toFloat()),
                                event.calculateCentroid(useCurrent = false),
                                scale * zoom,
                                pan,
                            )
                            event.changes.forEach {
                                if (it.position != it.previousPosition) it.consume()
                            }
                        }
                    }
                } while (event.changes.any { it.pressed })
            }
        }
    } else {
        Modifier
    }

    Box(Modifier.fillMaxSize().clipToBounds().then(gestures)) {
        AsyncImage(
            model = request,
            contentDescription = stringResource(R.string.viewer_page_position, number, count),
            modifier = imageModifier.fillMaxSize().graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = translation.x
                translationY = translation.y
            },
            contentScale = ContentScale.Fit,
            onSuccess = { imageSize = it.painter.intrinsicSize },
        )
        // The base image is screen-sized, so zooming would only stretch it: once zoomed in, the
        // visible part of the processed page is decoded at the zoom's resolution and drawn on top.
        ZoomTileLayer(processed?.first, active, scale = { scale }, translation = { translation })
        if (item.busy || processed == null) {
            Box(Modifier.align(Alignment.TopCenter).padding(12.dp)) { EditorProgress() }
        }
        if (page.glareWarning) {
            Surface(
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.errorContainer,
            ) {
                Text(stringResource(R.string.editor_glare_warning), Modifier.padding(8.dp))
            }
        }
    }
}

/** One toolbar action: an icon over a short label, 48dp+ tall. [emphasized] fills the icon's pill. */
@Composable
private fun ViewerAction(icon: Int, label: Int, enabled: Boolean, emphasized: Boolean = false, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier.clip(RoundedCornerShape(20.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .widthIn(min = 64.dp)
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .alpha(if (enabled) 1f else 0.38f),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Box(
            Modifier.size(width = 52.dp, height = 30.dp)
                .background(if (emphasized) colors.primary else Color.Transparent, RoundedCornerShape(15.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(icon),
                contentDescription = null,
                tint = if (emphasized) colors.onPrimary else colors.onSurface,
                modifier = Modifier.size(22.dp),
            )
        }
        Text(stringResource(label), style = MaterialTheme.typography.labelMedium, color = colors.onSurface)
    }
}
