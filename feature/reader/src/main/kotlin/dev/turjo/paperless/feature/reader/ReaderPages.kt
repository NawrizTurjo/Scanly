package dev.turjo.paperless.feature.reader

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.turjo.paperless.core.model.PageRect
import dev.turjo.paperless.core.model.PageLink
import dev.turjo.paperless.core.model.TextMatch
import dev.turjo.paperless.core.model.ReaderViewMode
import dev.turjo.paperless.core.model.TabState
import dev.turjo.paperless.core.pdf.PdfDocumentHandle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal class PageJump(val page: Int, val match: TextMatch? = null)

@Composable
internal fun ReaderPages(
    document: PdfDocumentHandle,
    tab: TabState,
    horizontalOffset: Float,
    search: SearchState,
    requestedJump: PageJump?,
    onJumpConsumed: () -> Unit,
    crop: CropMode,
    showThumbnails: Boolean,
    onDismissThumbnails: () -> Unit,
    onPosition: (Int, Int) -> Unit,
    onZoom: (Float, Float) -> Unit,
    annotationLayer: AnnotationLayer? = null,
    onAnnotation: AnnotationGesture? = null,
) {
    var showJump by remember { mutableStateOf(false) }
    var localJump by remember { mutableStateOf<PageJump?>(null) }
    val jump = requestedJump ?: localJump
    val consumed = { if (requestedJump != null) onJumpConsumed() else localJump = null }
    var history by remember { mutableStateOf(LinkHistory()) }
    var external by remember { mutableStateOf<String?>(null) }
    var linkError by remember { mutableStateOf(false) }
    LaunchedEffect(history) {
        if (history.previous != null) {
            delay(10_000)
            history = LinkHistory()
        }
    }
    var uniform by remember(document, crop) { mutableStateOf<PageRect?>(null) }
    LaunchedEffect(document, crop) {
        if (crop == CropMode.UNIFORM) {
            var bounds: PageRect? = null
            for (page in 0 until document.pageCount) {
                val box = readerResult { document.contentBox(page) } ?: continue
                bounds = bounds?.union(box) ?: box
            }
            uniform = bounds
        }
    }
    val onJump = { page: Int ->
        localJump = PageJump(page)
        showJump = false
        onDismissThumbnails()
    }
    val onLink: (Int, PageLink) -> Unit = { sourcePage, link ->
        val target = link.targetPage
        if (target != null && target in 0 until document.pageCount) {
            history = history.follow(sourcePage)
            onJump(target)
        } else {
            external = link.uri?.takeIf { it.startsWith("https://", true) || it.startsWith("http://", true) }
        }
    }
    Box(Modifier.fillMaxSize()) {
        key(tab.viewMode, crop, uniform) {
            when (tab.viewMode) {
                ReaderViewMode.CONTINUOUS -> ContinuousPages(
                    document, tab, horizontalOffset, jump, consumed, onPosition, onZoom, search, crop, uniform, onLink,
                    annotationLayer, onAnnotation,
                )
                ReaderViewMode.SINGLE_PAGE -> SinglePages(
                    document, tab, jump, consumed, onPosition, search, crop, uniform, onLink, annotationLayer, onAnnotation,
                )
            }
        }
        val displayPage = tab.page.coerceIn(0, document.pageCount - 1)
        FilledTonalButton(
            onClick = { showJump = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
        ) { Text(stringResource(R.string.reader_page_indicator, displayPage + 1, document.pageCount)) }
        history.previous?.let { previous ->
            FilledTonalButton(
                onClick = { history = history.back(); onJump(previous) },
                modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
            ) { Text("← p. ${previous + 1}") }
        }
        external?.let { ExternalLinkDialog(it, { external = null }, { linkError = true }) }
        if (linkError) androidx.compose.material3.AlertDialog(
            onDismissRequest = { linkError = false },
            text = { Text("No browser could open this link.") },
            confirmButton = { androidx.compose.material3.TextButton({ linkError = false }) { Text("OK") } },
        )
        if (showJump) JumpDialog(displayPage, document.pageCount, { showJump = false }, onJump)
        if (showThumbnails) {
            ThumbnailSheet(document, tab.nightMode, displayPage, onDismissThumbnails, onJump)
        }
    }
}

@Composable
private fun ContinuousPages(
    document: PdfDocumentHandle,
    tab: TabState,
    horizontalOffset: Float,
    jump: PageJump?,
    onJumpConsumed: () -> Unit,
    onPosition: (Int, Int) -> Unit,
    onZoom: (Float, Float) -> Unit,
    search: SearchState,
    crop: CropMode,
    uniform: PageRect?,
    onLink: (Int, PageLink) -> Unit,
    annotationLayer: AnnotationLayer?,
    onAnnotation: AnnotationGesture?,
) {
    val startingPage = remember { tab.page.coerceIn(0, document.pageCount - 1) }
    val startingOffset = remember { tab.scrollOffsetPx }
    val geometries = remember { mutableMapOf<Int, PageGeometry>() }
    val initialAspect = pageGeometry(document, startingPage, crop, uniform, geometries)?.aspect
    // Restore after measuring the starting page so its placeholder cannot clamp the saved offset.
    if (initialAspect == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }

    val list = rememberLazyListState(startingPage, startingOffset)
    val reportPosition by rememberUpdatedState(onPosition)
    val reportZoom by rememberUpdatedState(onZoom)
    var zoom by remember { mutableFloatStateOf(tab.zoom.coerceIn(1f, 5f)) }
    var panX by remember { mutableFloatStateOf(horizontalOffset) }
    var settledZoom by remember { mutableFloatStateOf(1f) }

    LaunchedEffect(zoom) {
        if (zoom > 1.5f) delay(250)
        settledZoom = if (zoom > 1.5f) zoom else 1f
    }
    LaunchedEffect(list) {
        snapshotFlow { list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset }
            .collect { (page, offset) -> reportPosition(page, offset) }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val viewportWidth = constraints.maxWidth.coerceAtLeast(1)
        val viewportHeight = constraints.maxHeight
        val lastAspect = pageGeometry(document, document.pageCount - 1, crop, uniform, geometries)?.aspect
        val shownWidth = (viewportWidth * zoom).roundToInt()
        LaunchedEffect(jump) {
            if (jump != null) {
                val geometry = geometries[jump.page] ?: readGeometry(document, jump.page, crop, uniform)
                    .also { geometries[jump.page] = it }
                val height = pageHeight(shownWidth, geometry.aspect)
                val transform = PageTransform(geometry.crop, shownWidth.toFloat(), height.toFloat())
                val bounds = jump.match?.rects?.firstOrNull()?.let(transform::map)
                if (bounds != null) {
                    panX = (bounds.left - viewportWidth / 4f).coerceIn(0f, (shownWidth - viewportWidth).toFloat())
                    reportZoom(zoom, panX)
                }
                val offset = bounds?.let { (it.top - viewportHeight / 3f).roundToInt() } ?: 0
                list.scrollToItem(jump.page, offset.coerceIn(0, height - 1))
                onJumpConsumed()
            }
        }
        val renderWidth = (viewportWidth * settledZoom).roundToInt()
        val endPadding = lastAspect?.let {
            with(density) { (viewportHeight - pageHeight(shownWidth, it)).coerceAtLeast(0).toDp() }
        } ?: 0.dp

        fun transform(anchor: Offset, pan: Offset, targetZoom: Float) {
            val nextZoom = targetZoom.coerceIn(1f, 5f)
            val scale = nextZoom / zoom
            val maxPan = viewportWidth * (nextZoom - 1f)
            panX = ((panX + anchor.x) * scale - anchor.x - pan.x).coerceIn(0f, maxPan)
            if (scale != 1f || pan.y != 0f) {
                val visible = list.layoutInfo.visibleItemsInfo
                val item = visible.lastOrNull { it.offset <= anchor.y } ?: visible.firstOrNull()
                if (item != null) {
                    val offset = ((anchor.y - item.offset) * scale - anchor.y - pan.y).roundToInt()
                    list.requestScrollToItem(item.index, offset)
                }
            }
            zoom = nextZoom
            reportZoom(zoom, panX)
        }

        val currentTransform by rememberUpdatedState(::transform)
        val gestures = Modifier
            .pointerInput(list) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var motion = Offset.Zero
                    var horizontalDrag = false
                    var pinching = false
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val fingers = event.changes.count { it.pressed && it.previousPressed }
                        val pan = event.calculatePan()
                        if (fingers >= 2) {
                            pinching = true
                            currentTransform(event.calculateCentroid(useCurrent = false), pan, zoom * event.calculateZoom())
                            event.changes.forEach { it.consume() }
                        } else if (pinching) {
                            event.changes.forEach { it.consume() }
                        } else if (zoom > 1f && fingers == 1) {
                            val afterChildren = awaitPointerEvent(PointerEventPass.Main)
                            if (afterChildren.changes.any { it.isConsumed }) break
                            motion += pan
                            if (abs(motion.x) > viewConfiguration.touchSlop && abs(motion.x) > abs(motion.y)) {
                                horizontalDrag = true
                            }
                            if (horizontalDrag) {
                                currentTransform(Offset.Zero, Offset(pan.x, 0f), zoom)
                                afterChildren.changes.forEach { it.consume() }
                            }
                        }
                    } while (event.changes.any { it.pressed } && (pinching || horizontalDrag || event.changes.none { it.isConsumed }))
                }
            }

        LazyColumn(
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer).then(gestures),
            state = list,
            contentPadding = PaddingValues(bottom = endPadding),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(document.pageCount) { page ->
                val geometry = pageGeometry(document, page, crop, uniform, geometries)
                val height = pageHeight(shownWidth, geometry?.aspect ?: initialAspect)
                val render = geometry?.let { pageBitmap(document, page, fullRenderWidth(renderWidth, it), tab.nightMode) }
                    ?: PageRender()
                val label = stringResource(R.string.reader_page_label, page + 1)
                val maxPan = (shownWidth - viewportWidth).coerceAtLeast(0).toFloat()
                Box(
                    Modifier.fillMaxWidth().height(with(density) { height.toDp() })
                        .clipToBounds().background(paperColor(tab.nightMode))
                        .semantics { contentDescription = label },
                    contentAlignment = Alignment.Center,
                ) {
                    val bitmap = render.bitmap
                    if (bitmap != null && geometry != null) {
                        PageOverlay(
                            document, page, bitmap, geometry,
                            PageTransform(geometry.crop, shownWidth.toFloat(), height.toFloat(), -panX.coerceIn(0f, maxPan)),
                            search, { onLink(page, it) }, annotationLayer, onAnnotation,
                        ) { point ->
                            val top = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == page }?.offset ?: 0
                            currentTransform(point + Offset(0f, top.toFloat()), Offset.Zero, if (zoom > 1f) 1f else 2.5f)
                        }
                    } else if (render.failed) {
                        PageRenderError(tab.nightMode)
                    }
                }
            }
        }
    }
}

@Composable
private fun SinglePages(
    document: PdfDocumentHandle,
    tab: TabState,
    jump: PageJump?,
    onJumpConsumed: () -> Unit,
    onPosition: (Int, Int) -> Unit,
    search: SearchState,
    crop: CropMode,
    uniform: PageRect?,
    onLink: (Int, PageLink) -> Unit,
    annotationLayer: AnnotationLayer?,
    onAnnotation: AnnotationGesture?,
) {
    val pager = rememberPagerState(tab.page.coerceIn(0, document.pageCount - 1)) { document.pageCount }
    val reportPosition by rememberUpdatedState(onPosition)
    val geometries = remember { mutableMapOf<Int, PageGeometry>() }
    LaunchedEffect(pager) { snapshotFlow { pager.currentPage }.collect { reportPosition(it, 0) } }
    LaunchedEffect(jump) {
        if (jump != null) {
            pager.scrollToPage(jump.page)
            onJumpConsumed()
        }
    }
    HorizontalPager(pager, Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer)) { page ->
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val geometry = pageGeometry(document, page, crop, uniform, geometries)
            if (geometry == null) {
                CircularProgressIndicator()
            } else {
                val width = minOf(constraints.maxWidth, (constraints.maxHeight / geometry.aspect).roundToInt()).coerceAtLeast(1)
                val height = pageHeight(width, geometry.aspect)
                val density = LocalDensity.current
                val render = pageBitmap(document, page, fullRenderWidth(width, geometry), tab.nightMode)
                Box(with(density) { Modifier.width(width.toDp()).height(height.toDp()) }
                    .clipToBounds().background(paperColor(tab.nightMode))) {
                    render.bitmap?.let {
                        PageOverlay(document, page, it, geometry,
                            PageTransform(geometry.crop, width.toFloat(), height.toFloat()), search, { link -> onLink(page, link) }, annotationLayer, onAnnotation)
                    }
                    if (render.failed) PageRenderError(tab.nightMode)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThumbnailSheet(
    document: PdfDocumentHandle,
    night: Boolean,
    currentPage: Int,
    onDismiss: () -> Unit,
    onJump: (Int) -> Unit,
) {
    val grid = rememberLazyGridState(currentPage)
    val ratios = remember { mutableMapOf<Int, Float>() }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            stringResource(R.string.reader_thumbnails),
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.titleLarge,
        )
        LazyVerticalGrid(
            columns = GridCells.Adaptive(96.dp),
            modifier = Modifier.weight(1f, fill = false),
            state = grid,
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(document.pageCount) { page ->
                Column(
                    Modifier.background(
                        if (page == currentPage) MaterialTheme.colorScheme.secondaryContainer
                        else MaterialTheme.colorScheme.surface,
                    ).clickable { onJump(page) }.padding(4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val aspect = pageAspect(document, page, ratios)
                    val frame = Modifier.fillMaxWidth().aspectRatio(0.72f)
                    if (aspect == null) {
                        Box(frame.background(paperColor(night)))
                    } else {
                        PagePreview(document, page, boundedRenderWidth(192, aspect), night, frame)
                    }
                    Text((page + 1).toString(), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun PagePreview(
    document: PdfDocumentHandle,
    page: Int,
    width: Int,
    night: Boolean,
    modifier: Modifier,
) {
    val render = pageBitmap(document, page, width, night)
    val label = stringResource(R.string.reader_page_label, page + 1)
    Box(
        modifier.background(paperColor(night))
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = render.bitmap
        if (bitmap != null) {
            Image(remember(bitmap) { bitmap.asImageBitmap() }, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        } else if (render.failed) {
            PageRenderError(night)
        }
    }
}

@Composable
private fun PageRenderError(night: Boolean) {
    Text(
        stringResource(R.string.reader_render_error),
        Modifier.padding(4.dp),
        color = if (night) Color.White else Color.Black,
    )
}

private fun paperColor(night: Boolean): Color =
    if (night) Color(0xFF222222) else Color(0xFFF2F2F2)

private data class PageRender(val bitmap: Bitmap? = null, val failed: Boolean = false)

@Composable
private fun pageBitmap(document: PdfDocumentHandle, page: Int, width: Int, night: Boolean): PageRender {
    var render by remember(document, page, night) { mutableStateOf(PageRender()) }
    LaunchedEffect(document, page, width, night) {
        render = try {
            PageRender(document.render(page, width, night))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            PageRender(failed = true)
        }
    }
    return render
}

@Composable
private fun pageAspect(document: PdfDocumentHandle, page: Int, cache: MutableMap<Int, Float>): Float? {
    var aspect by remember(document, page) { mutableStateOf(cache[page]) }
    LaunchedEffect(document, page) {
        if (aspect == null) {
            aspect = cache[page] ?: readAspect(document, page).also { cache[page] = it }
        }
    }
    return aspect
}

private suspend fun readAspect(document: PdfDocumentHandle, page: Int): Float = try {
    val size = document.pageSize(page)
    (size.height / size.width).takeIf { it.isFinite() && it > 0 } ?: DEFAULT_ASPECT
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    DEFAULT_ASPECT
}

private const val DEFAULT_ASPECT = 1.414f

private fun pageHeight(width: Int, aspect: Float): Int = (width * aspect).coerceIn(1f, 16_000f).roundToInt()

// One exceptional page or a 5x zoom should not allocate an unbounded bitmap.
private fun boundedRenderWidth(width: Int, aspect: Float): Int =
    minOf(width, sqrt(8_000_000f / aspect).toInt(), (16_000f / aspect).toInt()).coerceAtLeast(1)


@Composable
private fun pageGeometry(
    document: PdfDocumentHandle,
    page: Int,
    mode: CropMode,
    uniform: PageRect?,
    cache: MutableMap<Int, PageGeometry>,
): PageGeometry? {
    var geometry by remember(document, page, mode, uniform) { mutableStateOf(cache[page]) }
    LaunchedEffect(document, page, mode, uniform) {
        geometry = cache[page] ?: readGeometry(document, page, mode, uniform).also { cache[page] = it }
    }
    return geometry
}

private suspend fun readGeometry(document: PdfDocumentHandle, page: Int, mode: CropMode, uniform: PageRect?): PageGeometry {
    val size = readerResult { document.pageSize(page) }
    val full = PageRect(0f, 0f, size?.width?.coerceAtLeast(1f) ?: 1f, size?.height?.coerceAtLeast(1f) ?: DEFAULT_ASPECT)
    val detected = when (mode) {
        CropMode.OFF -> null
        CropMode.PAGE -> readerResult { document.contentBox(page) }
        CropMode.UNIFORM -> uniform
    }
    val crop = detected?.let {
        PageRect(maxOf(it.left, full.left), maxOf(it.top, full.top), minOf(it.right, full.right), minOf(it.bottom, full.bottom))
    }?.takeIf { it.right > it.left && it.bottom > it.top } ?: full
    return PageGeometry(full, crop)
}

private fun fullRenderWidth(width: Int, geometry: PageGeometry): Int {
    val full = geometry.full
    val crop = geometry.crop
    return boundedRenderWidth(
        (width * full.right / (crop.right - crop.left)).roundToInt(),
        full.bottom / full.right,
    )
}
