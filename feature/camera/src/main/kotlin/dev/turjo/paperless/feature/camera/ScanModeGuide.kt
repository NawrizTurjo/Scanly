package dev.turjo.paperless.feature.camera

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

internal val ScanMode.label: Int
    get() = when (this) {
        ScanMode.DOCUMENT -> R.string.camera_mode_document
        ScanMode.ID_CARD -> R.string.camera_mode_id
        ScanMode.BOOK -> R.string.camera_mode_book
        ScanMode.WHITEBOARD -> R.string.camera_mode_whiteboard
    }

@Composable
internal fun ScanModeGuide(mode: ScanMode, modifier: Modifier) {
    Canvas(modifier) {
        val aspect = if (mode == ScanMode.ID_CARD) 85.6f / 53.98f else 1.5f
        val width = minOf(size.width * 0.84f, size.height * 0.55f * aspect)
        val height = width / aspect
        val topLeft = Offset(center.x - width / 2, center.y - height / 2)
        val stroke = 2.dp.toPx()
        val color = Color.White.copy(alpha = 0.85f)
        drawRoundRect(color, topLeft, Size(width, height), CornerRadius(10.dp.toPx()), style = Stroke(stroke))
        if (mode == ScanMode.BOOK) {
            drawLine(color, Offset(center.x, topLeft.y), Offset(center.x, topLeft.y + height), stroke)
        }
    }
}
