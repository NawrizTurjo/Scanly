package dev.turjo.paperless.feature.camera

import android.os.SystemClock
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateOffsetAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

internal class LiveQuadSample(val quad: OverlayQuad, val uprightAspectRatio: Float)

@Composable
internal fun LiveQuadOverlay(sample: LiveQuadSample?, modifier: Modifier = Modifier) {
    var lastQuad by remember { mutableStateOf<LiveQuadSample?>(null) }
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(sample) {
        if (sample != null) {
            lastQuad = sample
            visible = true
            delay(300)
        }
        visible = false
    }

    val displayed = lastQuad ?: return
    val alpha by animateFloatAsState(if (visible) 1f else 0f, tween(150), label = "Quad opacity")
    val color by animateColorAsState(
        lerp(Color(0xFF2196F3), Color(0xFF4CAF50), stabilityColorFraction(displayed.quad.stableForMillis)),
        tween(80),
        label = "Quad stability",
    )
    val corners = List(4) { corner ->
        animateOffsetAsState(
            Offset(displayed.quad.points[corner * 2], displayed.quad.points[corner * 2 + 1]),
            tween(80),
            label = "Quad corner",
        )
    }

    Canvas(modifier) {
        if (alpha == 0f) return@Canvas
        val points = corners.map { corner ->
            val (x, y) = corner.value
            val point = mapToViewfinder(x, y, size.width, size.height, displayed.uprightAspectRatio)
            Offset(point.x, point.y)
        }
        val path = Path().apply {
            moveTo(points.first().x, points.first().y)
            for (point in points.drop(1)) lineTo(point.x, point.y)
            close()
        }
        clipRect {
            drawPath(path, color.copy(alpha = alpha * 0.25f))
            drawPath(path, color.copy(alpha = alpha), style = Stroke(3.dp.toPx()))
            for (point in points) drawCircle(color.copy(alpha = alpha), radius = 4.dp.toPx(), center = point)
        }
    }
}

@Composable
internal fun AnalysisFpsReadout(counter: AnalysisFpsCounter, modifier: Modifier = Modifier) {
    val fps by produceState(0, counter) {
        while (true) {
            value = counter.fps(SystemClock.elapsedRealtimeNanos())
            delay(250)
        }
    }
    Text(
        stringResource(R.string.camera_analysis_fps, fps),
        modifier.background(Color.Black.copy(alpha = 0.65f)).padding(horizontal = 6.dp, vertical = 2.dp),
        color = Color.White,
        style = MaterialTheme.typography.labelSmall,
    )
}
