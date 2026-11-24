package dev.turjo.paperless.feature.reader

import android.graphics.PointF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import dev.turjo.paperless.core.model.Annotation
import dev.turjo.paperless.core.model.AnnotationType
import dev.turjo.paperless.core.model.PdfKey

private val swatches = listOf(
    "Black" to 0xFF252525.toInt(), "Blue" to 0xFF215DC8.toInt(), "Red" to 0xFFCE3030.toInt(),
    "Yellow" to 0xFFFFD524.toInt(), "Green" to 0xFF278D49.toInt(), "Purple" to 0xFF813EB7.toInt(),
)

@Composable
internal fun AnnotationToolbar(
    layer: AnnotationLayer,
    update: ((AnnotationLayer) -> AnnotationLayer) -> Unit,
    stepHistory: (Boolean) -> Unit,
    done: () -> Unit,
) {
    if (layer.signaturePicker != null) {
        SignaturePicker(
            onDismiss = { update { it.copy(signaturePicker = null) } },
            openPadWhenEmpty = layer.signaturePicker == SignaturePickerMode.DRAW_IF_EMPTY,
            onPick = { signature ->
                update { it.copy(pendingSignature = signature, signaturePicker = null) }
            },
        )
    }
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        shape = MaterialTheme.shapes.large,
        tonalElevation = 4.dp,
        shadowElevation = 4.dp,
    ) {
        Column(Modifier.padding(horizontal = 6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
                    for (tool in AnnotationTool.entries) {
                        FilterChip(
                            selected = layer.tool == tool,
                            onClick = {
                                update {
                                    it.copy(
                                        tool = tool,
                                        pendingSignature = null,
                                        signaturePicker = SignaturePickerMode.PICK.takeIf { tool == AnnotationTool.SIGNATURE },
                                    )
                                }
                            },
                            label = {
                                Text(if (tool == AnnotationTool.SIGNATURE) stringResource(R.string.signature_title) else tool.label)
                            },
                            modifier = Modifier.padding(horizontal = 2.dp),
                        )
                    }
                }
                TextButton(done) { Text("Done") }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                if (layer.tool != AnnotationTool.SIGNATURE) {
                    for ((name, value) in swatches) {
                        Surface(
                            Modifier.size(48.dp).padding(7.dp)
                                .semantics { contentDescription = name; selected = layer.color == value }
                                .clickable(role = Role.Button) { update { it.copy(color = value) } },
                            shape = CircleShape,
                            color = Color(value),
                            border = if (layer.color == value) androidx.compose.foundation.BorderStroke(
                                2.dp, MaterialTheme.colorScheme.onSurface,
                            ) else null,
                            content = {},
                        )
                    }
                    for ((label, width) in listOf("Thin" to 1.5f, "Medium" to 3f, "Thick" to 6f)) {
                        FilterChip(layer.width == width, { update { it.copy(width = width) } }, { Text(label) },
                            modifier = Modifier.padding(horizontal = 2.dp))
                    }
                }
                TextButton({ stepHistory(false) }, enabled = layer.undo.isNotEmpty()) { Text("Undo") }
                TextButton({ stepHistory(true) }, enabled = layer.redo.isNotEmpty()) { Text("Redo") }
            }
            if (layer.tool == AnnotationTool.SIGNATURE) {
                Text(
                    stringResource(if (layer.pendingSignature == null) R.string.signature_edit_hint else R.string.signature_place_hint),
                    Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AnnotationSavePrompt(prompt: AnnotationPrompt, layer: AnnotationLayer, viewModel: ReaderViewModel) {
    val dismiss = viewModel::dismissAnnotationPrompt
    val save = { viewModel.saveAnnotations(prompt, flatten = true) }
    val discard = { viewModel.discardAnnotations(prompt) }
    if (prompt.closing) {
        AlertDialog(
            onDismissRequest = dismiss,
            title = { Text("Save unsaved annotations?") },
            confirmButton = { TextButton(save, enabled = !layer.saving) { Text("Save copy") } },
            dismissButton = {
                Row {
                    TextButton(dismiss, enabled = !layer.saving) { Text("Cancel") }
                    TextButton(discard, enabled = !layer.saving) { Text("Discard") }
                }
            },
        )
    } else {
        ModalBottomSheet(onDismissRequest = dismiss) {
            TextButton(save, enabled = !layer.saving) { Text("Save as new PDF (flattened)") }
            TextButton({ viewModel.saveAnnotations(prompt, flatten = false) }, enabled = !layer.saving) {
                Text("Keep as editable layer")
            }
            TextButton(discard, enabled = !layer.saving) { Text("Discard") }
        }
    }
}

internal typealias AnnotationGesture = (Int, AnnotationTool, Int, Float, List<PointF>, String?) -> Unit

@Composable
internal fun AnnotationOverlay(
    page: Int,
    transform: PageTransform,
    layer: AnnotationLayer,
    onGesture: AnnotationGesture?,
    night: Boolean,
) {
    val mapping by rememberUpdatedState(transform)
    val commit by rememberUpdatedState(onGesture)
    val active = layer.editing && !layer.saving && onGesture != null && layer.tool != AnnotationTool.MOVE && layer.tool != AnnotationTool.SIGNATURE
    val tool = layer.tool
    val color = layer.color
    val width = if (tool == AnnotationTool.HIGHLIGHTER) layer.width * 4 else layer.width
    var preview by remember(page, tool, color, width, active) { mutableStateOf<List<PointF>>(emptyList()) }
    val pageMarks = remember(layer.marks, page) { layer.marks.filter { it.page == page } }
    var noteAnchor by remember(page, tool, active) { mutableStateOf<PointF?>(null) }
    val gestures = if (active) Modifier.pointerInput(page, tool, color, width) {
        awaitEachGesture {
            fun toPage(position: Offset): PointF {
                val (x, y) = mapping.unmap(position.x, position.y)
                return PointF(x, y)
            }

            val down = awaitFirstDown()
            val points = mutableListOf(toPage(down.position))
            down.consume()
            if (tool == AnnotationTool.PEN || tool == AnnotationTool.HIGHLIGHTER) preview = points.toList()
            try {
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (change.isConsumed || event.changes.any { it.id != down.id && it.pressed }) break
                    val point = toPage(change.position)
                    if (point != points.last()) points += point
                    change.consume()
                    if (!change.pressed) {
                        if (tool == AnnotationTool.NOTE) noteAnchor = points.first()
                        else commit?.invoke(page, tool, color, width, points.toList(), null)
                        break
                    }
                    if (tool == AnnotationTool.PEN || tool == AnnotationTool.HIGHLIGHTER) preview = points.toList()
                }
            } finally {
                preview = emptyList()
            }
        }
    } else Modifier

    Canvas(Modifier.fillMaxSize().then(gestures)) {
        pageMarks.filterNot { it.type == AnnotationType.SIGNATURE }.forEach { drawAnnotation(it, transform, night) }
        if (preview.isNotEmpty()) {
            drawAnnotation(Annotation("", PdfKey(""), page, checkNotNull(tool.type), color, width, preview), transform, night)
        }
    }
    noteAnchor?.let { point ->
        var note by remember(point) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { noteAnchor = null },
            title = { Text("Text note") },
            text = { OutlinedTextField(note, { note = it.take(500) }, label = { Text("Note") }) },
            confirmButton = {
                TextButton({
                    commit?.invoke(page, tool, color, width, listOf(point), note.trim())
                    noteAnchor = null
                }, enabled = note.isNotBlank()) { Text("Add note") }
            },
            dismissButton = { TextButton({ noteAnchor = null }) { Text("Cancel") } },
        )
    }

}

private fun DrawScope.drawAnnotation(mark: Annotation, transform: PageTransform, night: Boolean) {
    fun point(x: Float, y: Float) = Offset(transform.mapX(x), transform.mapY(y))
    val color = Color(mark.color).let { if (mark.type == AnnotationType.HIGHLIGHT) it.copy(alpha = 0.35f) else it }
    // Dark marks need a light edge against inverted paper to retain their original color.
    val edge = if (night && color.luminance() < 0.12f) 1.dp.toPx() else 0f
    val edgeColor = Color.White.copy(alpha = 0.75f)
    val width = transform.mapX(mark.width) - transform.mapX(0f)
    for (rect in mark.rects) {
        val bounds = transform.map(rect)
        if (mark.type == AnnotationType.HIGHLIGHT) {
            val topLeft = Offset(bounds.left, bounds.top)
            val size = Size(bounds.right - bounds.left, bounds.bottom - bounds.top)
            drawRect(color, topLeft, size)
            if (edge > 0f) drawRect(edgeColor, topLeft, size, style = Stroke(edge))
        } else {
            val y = if (mark.type == AnnotationType.UNDERLINE) bounds.bottom else (bounds.top + bounds.bottom) / 2
            val start = Offset(bounds.left, y)
            val end = Offset(bounds.right, y)
            if (edge > 0f) drawLine(edgeColor, start, end, width + 2 * edge, StrokeCap.Round)
            drawLine(color, start, end, width, StrokeCap.Round)
        }
    }
    val first = mark.points.firstOrNull() ?: return
    val start = point(first.x, first.y)
    if (mark.type == AnnotationType.NOTE) {
        val end = point(first.x + 9, first.y + 9)
        val size = Size(end.x - start.x, end.y - start.y)
        drawRect(color, start, size)
        if (edge > 0f) drawRect(edgeColor, start, size, style = Stroke(edge))
        for (line in listOf(3f, 6f)) {
            drawLine(Color.White, point(first.x + 2, first.y + line), point(first.x + 7, first.y + line), width / mark.width)
        }
    } else if (mark.points.size == 1) {
        if (edge > 0f) drawCircle(edgeColor, width / 2 + edge, start)
        drawCircle(color, width / 2, start)
    } else {
        val path = Path().apply {
            moveTo(start.x, start.y)
            for ((a, b) in mark.points.zipWithNext()) {
                val control = point(a.x, a.y)
                val middle = point((a.x + b.x) / 2, (a.y + b.y) / 2)
                quadraticTo(control.x, control.y, middle.x, middle.y)
            }
            val last = mark.points.last()
            val end = point(last.x, last.y)
            lineTo(end.x, end.y)
        }
        val stroke = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round)
        if (edge > 0f) {
            drawPath(path, edgeColor, style = Stroke(width + 2 * edge, cap = stroke.cap, join = stroke.join))
        }
        drawPath(path, color, style = stroke)
    }
}
