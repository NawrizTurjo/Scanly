package dev.turjo.paperless.feature.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

internal fun pageTagLabel(first: Int, last: Int, total: Int): String =
    if (first == last) "$first / $total" else "$first–$last / $total"

/** Where along the track (0..1) page [page] (1-based) of [total] sits. */
internal fun pageTagFraction(page: Int, total: Int): Float =
    if (total <= 1) 0f else ((page - 1).toFloat() / (total - 1)).coerceIn(0f, 1f)

/** The 0-based page a drag to [fraction] (0..1) of the track lands on. */
internal fun pageForFraction(fraction: Float, total: Int): Int =
    (fraction.coerceIn(0f, 1f) * (total - 1)).roundToInt().coerceIn(0, maxOf(0, total - 1))

/**
 * Fast-scroll tag at the right edge: shows "4 / 7" while the pages scroll and, when [onDragToPage] is set, can be
 * dragged along the edge to jump through a long document (the label follows the finger).
 */
@Composable
internal fun ReaderPageTag(
    first: Int,
    last: Int,
    total: Int,
    scrolling: Boolean,
    modifier: Modifier,
    onDragToPage: ((Int) -> Unit)? = null,
) {
    if (total <= 1) return
    var dragging by remember { mutableStateOf(false) }
    var dragPage by remember { mutableIntStateOf(first) }
    var visible by remember { mutableStateOf(false) }
    val label = if (dragging) pageTagLabel(dragPage, dragPage, total) else pageTagLabel(first, last, total)
    var previous by remember { mutableStateOf(label) }
    LaunchedEffect(label, scrolling, dragging) {
        if (scrolling || dragging || label != previous) visible = true
        previous = label
        if (!scrolling && !dragging && visible) {
            delay(1_500)
            visible = false
        }
    }
    val jump by rememberUpdatedState(onDragToPage)
    val haptics = LocalHapticFeedback.current
    BoxWithConstraints(modifier.fillMaxHeight().padding(vertical = 8.dp)) {
        val trackPx = constraints.maxHeight.toFloat()
        var thumbPx by remember { mutableFloatStateOf(0f) }
        val target = pageTagFraction(if (dragging) dragPage else first, total)
        val fraction by animateFloatAsState(target, label = "Page tag position")
        val y = ((trackPx - thumbPx) * (if (dragging) target else fraction)).coerceAtLeast(0f)
        val startY by rememberUpdatedState(y)
        val startPage by rememberUpdatedState(first)
        AnimatedVisibility(
            visible,
            Modifier.align(Alignment.TopEnd).offset { IntOffset(0, y.roundToInt()) },
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            var dragY by remember { mutableFloatStateOf(0f) }
            Text(
                label,
                Modifier
                    .padding(end = 6.dp)
                    .onSizeChanged { thumbPx = it.height.toFloat() }
                    .then(
                        if (onDragToPage == null) Modifier else Modifier
                            // The right edge is the Back gesture zone; the handle must win there.
                            .systemGestureExclusion()
                            .pointerInput(total, trackPx) {
                                detectVerticalDragGestures(
                                    onDragStart = {
                                        dragging = true
                                        dragY = startY
                                        dragPage = startPage
                                    },
                                    onDragEnd = { dragging = false },
                                    onDragCancel = { dragging = false },
                                ) { change, delta ->
                                    change.consume()
                                    val range = (trackPx - thumbPx).coerceAtLeast(1f)
                                    dragY = (dragY + delta).coerceIn(0f, range)
                                    val page = pageForFraction(dragY / range, total)
                                    if (page + 1 != dragPage) {
                                        dragPage = page + 1
                                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        jump?.invoke(page)
                                    }
                                }
                            },
                    )
                    .background(
                        Color.Black.copy(alpha = if (dragging) 0.9f else 0.75f),
                        RoundedCornerShape(50),
                    )
                    .sizeIn(minHeight = if (onDragToPage != null) 40.dp else 0.dp)
                    .padding(horizontal = if (dragging) 14.dp else 10.dp, vertical = 10.dp)
                    .semantics { hideFromAccessibility() },
                color = Color.White,
                fontSize = if (dragging) 16.sp else 13.sp,
            )
        }
    }
}
