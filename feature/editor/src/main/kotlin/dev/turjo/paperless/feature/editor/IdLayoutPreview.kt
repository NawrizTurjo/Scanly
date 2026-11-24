package dev.turjo.paperless.feature.editor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.turjo.paperless.core.imaging.CardRect
import dev.turjo.paperless.core.imaging.IdCardComposer
import dev.turjo.paperless.core.imaging.IdCardComposer.PAGE_HEIGHT
import dev.turjo.paperless.core.imaging.IdCardComposer.PAGE_WIDTH
import dev.turjo.paperless.core.imaging.IdCardLayout
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private data class IdPreviewCard(val bitmap: Bitmap, val aspect: Float)

private fun loadCard(path: String): IdPreviewCard? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val options = BitmapFactory.Options().apply { inSampleSize = 1 }
    while (maxOf(bounds.outWidth, bounds.outHeight) / options.inSampleSize > 1600) {
        options.inSampleSize *= 2
    }
    val bitmap = BitmapFactory.decodeFile(path, options) ?: return null
    return IdPreviewCard(bitmap, IdCardComposer.cardAspect(bounds.outWidth, bounds.outHeight))
}

@Composable
internal fun IdLayoutPreview(
    state: EditorState,
    layout: IdCardLayout,
    enabled: Boolean,
    modifier: Modifier,
    onSelect: (Int) -> Unit,
    onFinish: (IdCardLayout) -> Unit,
) {
    val paths = state.idSourcePaths
    val cards by produceState<List<IdPreviewCard?>?>(null, paths) {
        value = withContext(Dispatchers.IO) { paths.map { it?.let(::loadCard) } }
    }
    val indices = state.idSourceOrder()
    val sources = cards?.let { loaded -> indices.mapNotNull { loaded.getOrNull(it) } }
        ?.takeIf { it.isNotEmpty() && it.size == indices.size }
    val label = stringResource(R.string.editor_id_layout)
    Box(modifier, contentAlignment = Alignment.Center) {
        if (sources == null) {
            AsyncImage(state.page?.originalPath?.let(::File), label, Modifier.fillMaxSize())
        } else {
            IdPlacementPage(
                layout, sources, indices, paths.size, state.idSelectedSource, enabled, label, onSelect, onFinish,
            )
        }
    }
}

@Composable
private fun IdPlacementPage(
    layout: IdCardLayout,
    sources: List<IdPreviewCard>,
    indices: List<Int>,
    sourceCount: Int,
    selectedSource: Int,
    enabled: Boolean,
    label: String,
    onSelect: (Int) -> Unit,
    onFinish: (IdCardLayout) -> Unit,
) {
    var draft by remember { mutableStateOf<IdCardLayout?>(null) }
    var snap by remember { mutableStateOf<CardSnap?>(null) }
    val finish by rememberUpdatedState(onFinish)
    val select by rememberUpdatedState(onSelect)
    val selectedIndex by rememberUpdatedState(indices.indexOf(selectedSource).coerceAtLeast(0))
    val visible = draft ?: layout
    val aspects = sources.map { it.aspect }
    val rects = IdCardComposer.cardRects(visible, aspects, indices)
    val selected = rects[selectedIndex]
    val accent = MaterialTheme.colorScheme.primary
    val targetSize = 48.dp
    val targetPx = with(LocalDensity.current) { targetSize.roundToPx() }

    BoxWithConstraints(
        Modifier.aspectRatio(PAGE_WIDTH.toFloat() / PAGE_HEIGHT)
            .semantics { contentDescription = label }
            .pointerInput(layout, sources, indices, enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown()
                    if (size.width == 0 || size.height == 0) return@awaitEachGesture
                    val scale = size.width.toFloat() / PAGE_WIDTH
                    val startRects = IdCardComposer.cardRects(layout, aspects, indices)
                    val previousSelection = selectedIndex
                    val active = startRects[previousSelection]
                    val origin = active.handleOrigin(scale, size.width, size.height, targetPx)
                    val resize = down.position.x in origin.x.toFloat()..(origin.x + targetPx).toFloat() &&
                        down.position.y in origin.y.toFloat()..(origin.y + targetPx).toFloat()
                    val point = down.position / scale
                    val hits = startRects.indices.filter { startRects[it].contains(point) }
                    val index = when {
                        resize || previousSelection in hits -> previousSelection
                        else -> hits.lastOrNull() ?: return@awaitEachGesture
                    }
                    select(indices[index])
                    down.consume()
                    val start = startRects[index]
                    val others = startRects.filterIndexed { i, _ -> i != index }
                    val aspect = aspects[index]
                    val startPlacement = start.placement()
                    var pan = Offset.Zero
                    var zoom = 1f
                    var pinching = false
                    var dragging = false
                    var cancelled = false
                    try {
                        do {
                            val event = awaitPointerEvent()
                            if (event.changes.any { it.isConsumed }) {
                                cancelled = true
                                break
                            }
                            pan += event.calculatePan()
                            val zoomChange = event.calculateZoom()
                            zoom *= zoomChange
                            pinching = pinching || zoomChange != 1f
                            val zoomMotion = kotlin.math.abs(zoom - 1f) * start.width * scale
                            if (!dragging && maxOf(pan.getDistance(), zoomMotion) < viewConfiguration.touchSlop) {
                                continue
                            }
                            dragging = true
                            val resizing = resize || pinching
                            val widthDelta = if (resize) {
                                val slope = 1f / aspect
                                (pan.x + pan.y * slope) / (1f + slope * slope) / size.width
                            } else 0f
                            val proposed = if (resizing) {
                                startPlacement.copy(width = startPlacement.width * zoom + widthDelta)
                            } else {
                                startPlacement.copy(
                                    left = startPlacement.left + pan.x / size.width,
                                    top = startPlacement.top + pan.y / size.height,
                                )
                            }
                            val rect = IdCardComposer.placementRect(proposed, aspect)
                            val aligned = if (resizing) null else snapCard(rect, others, 5.dp.toPx() / scale)
                            snap = aligned
                            val placement = (aligned?.rect ?: rect).placement()
                            val placements = layout.placements(sourceCount).toMutableList()
                            startRects.forEachIndexed { i, bounds -> placements[indices[i]] = bounds.placement() }
                            placements[indices[index]] = placement
                            if (sourceCount == 2 && indices.size == 1) {
                                val absent = 1 - indices.single()
                                placements[absent] = placements[absent] ?: startPlacement
                            }
                            draft = layout.withPlacements(placements)
                            event.changes.forEach { it.consume() }
                        } while (event.changes.any { it.pressed })
                        if (!cancelled) {
                            draft?.let(finish)
                            if (!dragging && !resize && hits.size > 1) {
                                select(indices[hits[(hits.indexOf(index) + 1) % hits.size]])
                            }
                        }
                    } finally {
                        draft = null
                        snap = null
                    }
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val scale = size.width / PAGE_WIDTH
            drawRect(Color.White)
            scale(scale, pivot = Offset.Zero) {
                drawIntoCanvas {
                    sources.zip(rects).forEach { (source, rect) ->
                        IdCardComposer.drawCard(it.nativeCanvas, source.bitmap, rect, visible.outline)
                    }
                }
            }
            snap?.guideX?.let { x ->
                drawLine(accent, Offset(x * scale, 0f), Offset(x * scale, size.height), 1.dp.toPx())
            }
            snap?.guideY?.let { y ->
                drawLine(accent, Offset(0f, y * scale), Offset(size.width, y * scale), 1.dp.toPx())
            }
            drawRect(
                accent, Offset(selected.left * scale, selected.top * scale),
                Size(selected.width * scale, selected.height * scale), style = Stroke(2.dp.toPx()),
            )
            val radius = 5.dp.toPx()
            drawCircle(
                accent, radius,
                Offset((selected.right * scale).coerceAtMost(size.width - radius),
                    (selected.bottom * scale).coerceAtMost(size.height - radius)),
            )
        }
        if (enabled) {
            val pageWidth = constraints.maxWidth
            val pageHeight = constraints.maxHeight
            val description = when {
                sourceCount > 2 -> stringResource(R.string.editor_id_resize_side, selectedSource + 1)
                selectedSource == 0 -> stringResource(R.string.editor_id_resize_front)
                else -> stringResource(R.string.editor_id_resize_back)
            }
            Box(Modifier.offset {
                selected.handleOrigin(
                    pageWidth.toFloat() / PAGE_WIDTH, pageWidth, pageHeight, targetPx,
                )
            }.size(targetSize).systemGestureExclusion().semantics { contentDescription = description })
        }
    }
}

private fun CardRect.contains(point: Offset) =
    point.x in left.toFloat()..right.toFloat() && point.y in top.toFloat()..bottom.toFloat()

private fun CardRect.handleOrigin(scale: Float, pageWidth: Int, pageHeight: Int, target: Int) = IntOffset(
    ((right * scale).roundToInt() - target / 2).coerceIn(0, (pageWidth - target).coerceAtLeast(0)),
    ((bottom * scale).roundToInt() - target / 2).coerceIn(0, (pageHeight - target).coerceAtLeast(0)),
)
