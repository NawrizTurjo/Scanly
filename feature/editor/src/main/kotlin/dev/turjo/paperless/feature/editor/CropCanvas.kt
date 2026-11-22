package dev.turjo.paperless.feature.editor

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import dev.turjo.paperless.core.data.PixelSize
import dev.turjo.paperless.core.model.Quad
import kotlin.math.roundToInt

@Composable
internal fun CropCanvas(
    bitmap: Bitmap,
    imageSize: PixelSize,
    quad: Quad?,
    enabled: Boolean,
    modifier: Modifier,
    onStart: () -> Unit,
    onEnd: () -> Unit,
    onCrop: (Quad) -> Unit,
) {
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    val width = imageSize.width.toFloat()
    val height = imageSize.height.toFloat()
    val crop = quad ?: fullCrop(width, height)
    val latestCrop by rememberUpdatedState(crop)
    val startGesture by rememberUpdatedState(onStart)
    val finishGesture by rememberUpdatedState(onEnd)
    val changeCrop by rememberUpdatedState(onCrop)
    var loupe by remember { mutableStateOf<CropPoint?>(null) }
    val accent = MaterialTheme.colorScheme.primary
    val handleFill = MaterialTheme.colorScheme.surface
    val touchRadius = with(LocalDensity.current) { 28.dp.toPx() }
    // Exclusion rects follow the crop when a drag ends, not on every move (re-keying mid-drag would cancel it).
    var settledCrop by remember { mutableStateOf(crop) }
    if (loupe == null && settledCrop != crop) settledCrop = crop

    Box(modifier) {
        Canvas(
            Modifier.fillMaxSize().pointerInput(imageSize, enabled) {
                if (!enabled) return@pointerInput
                var handle = -1
                var startingCrop = latestCrop
                var displayedCrop = startingCrop
                var movement = Offset.Zero
                var finger = Offset.Zero
                fun finish() {
                    if (handle >= 0) finishGesture()
                    handle = -1
                    loupe = null
                }

                try {
                    detectDragGestures(
                        onDragStart = { position ->
                            val fit = CropFit(size.width.toFloat(), size.height.toFloat(), width, height, touchRadius)
                            startingCrop = latestCrop
                            displayedCrop = startingCrop
                            val handles = startingCrop.handles().map { fit.toView(it).toOffset() }
                            handle = handles.indices.minBy { (handles[it] - position).getDistanceSquared() }
                            if ((handles[handle] - position).getDistance() <= 24.dp.toPx()) {
                                finger = position
                                movement = Offset.Zero
                                loupe = fit.toImage(finger.toCropPoint()).clamp(width, height)
                                startGesture()
                            } else {
                                handle = -1
                            }
                        },
                        onDragEnd = ::finish,
                        onDragCancel = ::finish,
                    ) { change, delta ->
                        if (handle >= 0) {
                            change.consume()
                            movement += delta
                            finger += delta
                            val fit = CropFit(size.width.toFloat(), size.height.toFloat(), width, height, touchRadius)
                            loupe = fit.toImage(finger.toCropPoint()).clamp(width, height)
                            val current = startingCrop.moveHandle(
                                handle, movement.toCropPoint() * (1f / fit.scale), width, height,
                            )
                            if (current != null && current != displayedCrop) {
                                displayedCrop = current
                                changeCrop(current)
                            }
                        }
                    }
                } finally {
                    finish()
                }
            },
        ) {
            val fit = CropFit(size.width, size.height, width, height, touchRadius)
            drawImage(
                image,
                dstOffset = fit.origin.toOffset().toIntOffset(),
                dstSize = IntSize(fit.width.roundToInt(), fit.height.roundToInt()),
            )

            val corners = crop.points().map { fit.toView(it).toOffset() }
            val outline = Path().apply {
                moveTo(corners.first().x, corners.first().y)
                corners.drop(1).forEach { lineTo(it.x, it.y) }
                close()
            }
            drawPath(outline, accent.copy(alpha = 0.18f))
            drawPath(outline, accent, style = Stroke(2.dp.toPx()))
            crop.handles().forEachIndexed { index, point ->
                val position = fit.toView(point).toOffset()
                val radius = (if (index < 4) 8.dp else 5.dp).toPx()
                drawCircle(handleFill, radius, position)
                drawCircle(accent, radius, position, style = Stroke(2.dp.toPx()))
            }

            loupe?.let { point ->
                val radius = minOf(60.dp.toPx(), size.width / 2, size.height / 2)
                val margin = minOf(8.dp.toPx(), (size.width - 2 * radius) / 2)
                val x = if (fit.toView(point).x < size.width / 2) size.width - radius - margin else radius + margin
                val center = Offset(x, radius + minOf(8.dp.toPx(), size.height - 2 * radius))
                val lens = Path().apply { addOval(androidx.compose.ui.geometry.Rect(center, radius)) }
                val zoom = fit.scale * 2.5f
                clipPath(lens) {
                    drawCircle(handleFill, radius, center)
                    drawImage(
                        image,
                        dstOffset = (center - (point * zoom).toOffset()).toIntOffset(),
                        dstSize = IntSize((width * zoom).roundToInt(), (height * zoom).roundToInt()),
                    )
                    val length = 10.dp.toPx()
                    translate(center.x, center.y) {
                        for ((color, stroke) in listOf(Color.Black to 3.dp, Color.White to 1.dp)) {
                            drawLine(color, Offset(-length, 0f), Offset(length, 0f), stroke.toPx())
                            drawLine(color, Offset(0f, -length), Offset(0f, length), stroke.toPx())
                        }
                    }
                }
                drawCircle(handleFill, radius, center, style = Stroke(3.dp.toPx()))
            }
        }
        // With gesture navigation, a sideways drag that starts near the screen edge is a Back swipe, so handles next to the
        // edge could not be dragged on the phone. A touch-transparent layer excludes a square around every handle from
        // system gestures (Android allows 200 dp per edge; three 56 dp squares per edge stay within it).
        key(settledCrop) {
            val handles = settledCrop.handles()
            handles.indices.fold(Modifier.fillMaxSize()) { chain, index ->
                chain.systemGestureExclusion { coordinates ->
                    val fit = CropFit(coordinates.size.width.toFloat(), coordinates.size.height.toFloat(), width, height, touchRadius)
                    Rect(fit.toView(handles[index]).toOffset(), touchRadius)
                }
            }.let { Spacer(it) }
        }
    }
}

private fun CropPoint.toOffset() = Offset(x, y)
private fun Offset.toCropPoint() = CropPoint(x, y)
private fun Offset.toIntOffset() = IntOffset(x.roundToInt(), y.roundToInt())
