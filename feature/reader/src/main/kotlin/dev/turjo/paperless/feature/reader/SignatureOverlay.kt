package dev.turjo.paperless.feature.reader

import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.turjo.paperless.core.data.SavedSignature
import dev.turjo.paperless.core.imaging.fitSignatureImage
import dev.turjo.paperless.core.model.PageRect
import dev.turjo.paperless.core.model.SignaturePlacement
import dev.turjo.paperless.core.model.scaleFromUnit
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

internal typealias SignatureGesture = (Int, String?, SignaturePlacement?) -> Unit

@Composable
fun SignatureOverlay(
    placements: List<SignaturePlacement>,
    pending: SavedSignature?,
    onChange: ((Int?, SignaturePlacement?) -> Unit)?,
    modifier: Modifier = Modifier,
    pageArea: PageRect? = null,
    colorFilter: ColorFilter? = null,
) {
    val store = rememberSignatureStore()
    val scope = rememberCoroutineScope()
    var placing by remember { mutableStateOf(false) }
    var placementError by remember(pending?.id) { mutableStateOf(false) }
    val active = onChange != null
    val latestPending by rememberUpdatedState(pending)
    val latestPlacements by rememberUpdatedState(placements)
    val latestChange by rememberUpdatedState(onChange)
    val latestArea by rememberUpdatedState(pageArea)
    var selection by remember(active) { mutableStateOf<Pair<Int, SignaturePlacement>?>(null) }
    var draft by remember { mutableStateOf<SignaturePlacement?>(null) }
    val ids = placements.map { it.signatureId }.distinct()
    val images = ids.associateWith { id ->
        key(id) {
            val image by produceState<ImageBitmap?>(null, id) {
                value = readerResult { store.bitmap(id, placed = true)?.asImageBitmap() }
            }
            image
        }
    }
    val outline = MaterialTheme.colorScheme.primary
    val gestures = if (active) Modifier.pointerInput(pending?.id) {
        awaitEachGesture {
            val down = awaitFirstDown()
            if (placing) return@awaitEachGesture
            val area = latestArea ?: size.bounds()
            val width = area.right - area.left
            val height = area.bottom - area.top
            fun Offset.toPage() = Offset((x - area.left) / width, (y - area.top) / height)
            val point = down.position.toPage()
            if (point.x !in 0f..1f || point.y !in 0f..1f) return@awaitEachGesture
            val hits = latestPlacements
            val hit = hits.indexOfLast { (_, rect) ->
                point.x in rect.left..rect.right && point.y in rect.top..rect.bottom
            }
            val original = hits.getOrNull(hit)
            down.consume()
            if (pending != null) {
                var tapped = true
                do {
                    val event = awaitPointerEvent()
                    val finger = event.changes.firstOrNull { it.id == down.id } ?: break
                    tapped = tapped && !finger.isConsumed && event.changes.size == 1 &&
                        (finger.position - down.position).getDistance() <= viewConfiguration.touchSlop
                    finger.consume()
                    if (!finger.pressed && tapped) {
                        val rect = fitSignature(point, 0.28f, pending.aspect * height / width)
                        val placement = SignaturePlacement(pending.id, rect)
                        placing = true
                        placementError = false
                        scope.launch {
                            try {
                                val retained = readerResult { store.retainForPlacement(pending.id) }
                                if (latestPending != pending) return@launch
                                if (retained == null) {
                                    placementError = true
                                    return@launch
                                }
                                val index = latestPlacements.size
                                latestChange?.invoke(null, placement)
                                selection = index to placement
                            } finally {
                                placing = false
                            }
                        }
                    }
                } while (finger.pressed)
                return@awaitEachGesture
            }
            selection = original?.let { hit to it }
            if (original == null) return@awaitEachGesture
            var current: SignaturePlacement = original
            try {
                while (true) {
                    val event = awaitPointerEvent()
                    if (event.changes.any { it.isConsumed }) break
                    val delta = event.calculatePan()
                    val zoom = event.calculateZoom()
                    val rect = current.bounds
                    if (delta != Offset.Zero || zoom != 1f) {
                        val center = Offset((rect.left + rect.right) / 2 + delta.x / width,
                            (rect.top + rect.bottom) / 2 + delta.y / height)
                        val next = fitSignature(center, (rect.right - rect.left) * zoom,
                            (rect.right - rect.left) / (rect.bottom - rect.top))
                        current = current.copy(bounds = next)
                        draft = current
                    }
                    event.changes.forEach { it.consume() }
                    if (event.changes.none { it.pressed }) {
                        if (current != original && latestPlacements.getOrNull(hit) == original) {
                            latestChange?.invoke(hit, current)
                            selection = hit to current
                        }
                        break
                    }
                }
            } finally {
                draft = null
            }
        }
    } else Modifier
    val selected = selection?.takeIf { (index, mark) -> placements.getOrNull(index) == mark }?.first
    Box(modifier.then(gestures)) {
        Canvas(Modifier.fillMaxSize()) {
            val area = pageArea ?: PageRect(0f, 0f, size.width, size.height)
            placements.forEachIndexed { index, placement ->
                val marked = active && selected == index
                val current = if (marked) draft ?: placement else placement
                val rect = current.bounds.scaleFromUnit(area)
                val width = rect.right - rect.left
                val height = rect.bottom - rect.top
                images[current.signatureId]?.let { image ->
                    val target = fitSignatureImage(RectF(rect.left, rect.top, rect.right, rect.bottom), image.width, image.height)
                    drawImage(image, colorFilter = colorFilter, dstOffset = IntOffset(target.left.roundToInt(), target.top.roundToInt()),
                        dstSize = IntSize(target.width().roundToInt().coerceAtLeast(1), target.height().roundToInt().coerceAtLeast(1)))
                }
                if (marked) {
                    drawRect(outline, Offset(rect.left, rect.top), Size(width, height), style = Stroke(2.dp.toPx()))
                }
            }
        }
        if (active && placementError) {
            Surface(Modifier.offset(4.dp, 4.dp), shape = MaterialTheme.shapes.medium, tonalElevation = 4.dp) {
                Text(stringResource(R.string.signature_error))
            }
        }
        val index = selected
        if (active && index != null && placements.getOrNull(index) != null && draft == null) {
            Surface(Modifier.offset(4.dp, 4.dp), shape = MaterialTheme.shapes.medium, tonalElevation = 4.dp) {
                TextButton({
                    latestChange?.invoke(index, null)
                    selection = null
                }) { Text(stringResource(R.string.signature_remove_placement)) }
            }
        }
    }
}

private fun IntSize.bounds() = PageRect(0f, 0f, width.toFloat(), height.toFloat())

internal fun fitSignature(center: Offset, requestedWidth: Float, ratio: Float): PageRect {
    val maxWidth = minOf(1f, ratio)
    val width = requestedWidth.coerceIn(minOf(0.025f, maxWidth), maxWidth)
    val height = (width / ratio).coerceAtMost(1f)
    val left = (center.x - width / 2).coerceIn(0f, 1f - width)
    val top = (center.y - height / 2).coerceIn(0f, 1f - height)
    return PageRect(left, top, left + width, top + height)
}

