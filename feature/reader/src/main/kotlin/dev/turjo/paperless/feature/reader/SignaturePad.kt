package dev.turjo.paperless.feature.reader

import android.graphics.Bitmap
import android.graphics.Canvas as InkCanvas
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.turjo.paperless.core.data.SavedSignature
import dev.turjo.paperless.core.data.SignatureStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Composable
internal fun rememberSignatureStore(): SignatureStore {
    val context = LocalContext.current.applicationContext
    return remember(context) { SignatureStore(context) }
}

@Composable
fun SignaturePicker(
    onDismiss: () -> Unit,
    openPadWhenEmpty: Boolean = false,
    onPick: (SavedSignature) -> Unit,
) {
    val store = rememberSignatureStore()
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf<List<SavedSignature>?>(null) }
    var drawing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<SavedSignature?>(null) }
    LaunchedEffect(store) {
        entries = readerResult { store.list() }
        error = entries == null
        if (openPadWhenEmpty && entries?.isEmpty() == true) drawing = true
    }
    if (drawing) {
        SignaturePad(
            onDismiss = { if (openPadWhenEmpty && entries?.isEmpty() == true) onDismiss() else drawing = false },
            onSave = { store.save(it) },
            onSaved = onPick,
        )
        return
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.signature_title)) },
        text = {
            Column {
                if (error) Text(stringResource(R.string.signature_error))
                entries.orEmpty().forEachIndexed { index, signature ->
                    val label = stringResource(R.string.signature_saved, index + 1)
                    val bitmap by produceState<Bitmap?>(null, signature.id) {
                        value = readerResult { store.bitmap(signature.id) }
                    }
                    Row {
                        OutlinedButton({ onPick(signature) }, Modifier.weight(1f), enabled = !busy && bitmap != null) {
                            val image = bitmap
                            if (image != null) {
                                Image(image.asImageBitmap(), label, Modifier.fillMaxWidth().height(40.dp).background(Color.White))
                            } else {
                                Text(label)
                            }
                        }
                        TextButton({ deleting = signature }, enabled = !busy) {
                            Text(stringResource(R.string.signature_delete))
                        }
                    }
                }
                if (entries?.size == SignatureStore.LIMIT) Text(stringResource(R.string.signature_limit))
            }
        },
        confirmButton = {
            TextButton({ drawing = true }, enabled = !busy && entries?.let { it.size < SignatureStore.LIMIT } == true) {
                Text(stringResource(R.string.signature_new))
            }
        },
        dismissButton = { TextButton(onDismiss, enabled = !busy) { Text(stringResource(R.string.signature_cancel)) } },
    )
    deleting?.let { signature ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.signature_delete_saved)) },
            text = { Text(stringResource(R.string.signature_delete_detail)) },
            confirmButton = {
                TextButton({
                    deleting = null
                    busy = true
                    scope.launch {
                        val updated = readerResult {
                            store.delete(signature.id)
                            store.list()
                        }
                        if (updated != null) entries = updated
                        error = updated == null
                        busy = false
                    }
                }) { Text(stringResource(R.string.signature_delete)) }
            },
            dismissButton = { TextButton({ deleting = null }) { Text(stringResource(R.string.signature_cancel)) } },
        )
    }
}

private data class InkStroke(val points: List<Offset>, val color: Int, val width: Float)

@Composable
private fun SignaturePad(
    onDismiss: () -> Unit,
    onSave: suspend (Bitmap) -> SavedSignature,
    onSaved: (SavedSignature) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val strokes = remember { mutableStateListOf<InkStroke>() }
    var draft by remember { mutableStateOf<InkStroke?>(null) }
    var color by remember { mutableIntStateOf(android.graphics.Color.BLACK) }
    var thickness by remember { mutableFloatStateOf(4f) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    val canvasLabel = stringResource(R.string.signature_draw_area)
    val thicknessLabel = stringResource(R.string.signature_thickness)
    Dialog({ if (!saving) onDismiss() }, DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.safeDrawingPadding().padding(16.dp)) {
                Text(stringResource(R.string.signature_new), style = MaterialTheme.typography.headlineSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(color == android.graphics.Color.BLACK, { color = android.graphics.Color.BLACK },
                        { Text(stringResource(R.string.signature_black)) }, enabled = !saving)
                    FilterChip(color == BLUE, { color = BLUE },
                        { Text(stringResource(R.string.signature_blue)) }, enabled = !saving)
                }
                Text(thicknessLabel)
                Slider(thickness, { thickness = it }, valueRange = 2f..12f, enabled = !saving,
                    modifier = Modifier.semantics { contentDescription = thicknessLabel })
                Canvas(
                    Modifier.weight(1f).fillMaxWidth().background(Color.White)
                        .semantics { contentDescription = canvasLabel }
                        .pointerInput(color, thickness, saving) {
                            if (saving) return@pointerInput
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                val points = mutableListOf(down.position)
                                val width = thickness.dp.toPx()
                                canvasSize = size
                                down.consume()
                                draft = InkStroke(points.toList(), color, width)
                                try {
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                        if (change.isConsumed) break
                                        val point = Offset(change.position.x.coerceIn(0f, size.width.toFloat()),
                                            change.position.y.coerceIn(0f, size.height.toFloat()))
                                        if (point != points.last()) points += point
                                        change.consume()
                                        draft = InkStroke(points.toList(), color, width)
                                        if (!change.pressed) {
                                            strokes += checkNotNull(draft)
                                            break
                                        }
                                    }
                                } finally {
                                    draft = null
                                }
                            }
                        },
                ) {
                    drawIntoCanvas { canvas ->
                        strokes.forEach { canvas.nativeCanvas.drawInk(it) }
                        draft?.let { canvas.nativeCanvas.drawInk(it) }
                    }
                }
                if (error) Text(stringResource(R.string.signature_error))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onDismiss, enabled = !saving) { Text(stringResource(R.string.signature_cancel)) }
                    TextButton({ strokes.removeAt(strokes.lastIndex) }, enabled = strokes.isNotEmpty() && !saving) {
                        Text(stringResource(R.string.signature_undo))
                    }
                    TextButton({ strokes.clear() }, enabled = strokes.isNotEmpty() && !saving) {
                        Text(stringResource(R.string.signature_clear))
                    }
                    Button({
                        val snapshot = strokes.toList()
                        val size = canvasSize
                        saving = true
                        scope.launch {
                            try {
                                val bitmap = withContext(Dispatchers.Default) { renderInk(snapshot, size) }
                                try {
                                    onSaved(onSave(bitmap))
                                } finally {
                                    bitmap.recycle()
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                error = true
                            } finally {
                                saving = false
                            }
                        }
                    }, enabled = strokes.isNotEmpty() && !saving) { Text(stringResource(R.string.signature_save)) }
                }
            }
        }
    }
}

private val BLUE = 0xFF1648AE.toInt()

private fun InkCanvas.drawInk(stroke: InkStroke) {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = stroke.color
        strokeWidth = stroke.width
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    val first = stroke.points.first()
    if (stroke.points.size == 1) {
        drawCircle(first.x, first.y, stroke.width / 2f, paint)
        return
    }
    paint.style = Paint.Style.STROKE
    val path = Path().apply {
        moveTo(first.x, first.y)
        for ((a, b) in stroke.points.zipWithNext()) {
            quadTo(a.x, a.y, (a.x + b.x) / 2, (a.y + b.y) / 2)
        }
        val last = stroke.points.last()
        lineTo(last.x, last.y)
    }
    drawPath(path, paint)
}

private fun renderInk(strokes: List<InkStroke>, size: IntSize): Bitmap {
    val scale = minOf(1f, 1600f / maxOf(size.width, size.height))
    val width = (size.width * scale).roundToInt().coerceAtLeast(1)
    val height = (size.height * scale).roundToInt().coerceAtLeast(1)
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    var trimmed: Bitmap? = null
    try {
        InkCanvas(bitmap).apply {
            scale(scale, scale)
            strokes.forEach { drawInk(it) }
        }
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val ink = checkNotNull(inkBounds(pixels, width, height, margin = 8))
        trimmed = Bitmap.createBitmap(bitmap, ink.left, ink.top, ink.right - ink.left, ink.bottom - ink.top)
        return trimmed
    } finally {
        if (trimmed !== bitmap) bitmap.recycle()
    }
}

internal fun inkBounds(pixels: IntArray, width: Int, height: Int, margin: Int): androidx.compose.ui.unit.IntRect? {
    var left = width
    var top = height
    var right = -1
    var bottom = -1
    for (y in 0 until height) {
        for (x in 0 until width) {
            if (pixels[y * width + x] ushr 24 == 0) continue
            left = minOf(left, x)
            top = minOf(top, y)
            right = maxOf(right, x)
            bottom = maxOf(bottom, y)
        }
    }
    if (right < 0) return null
    return androidx.compose.ui.unit.IntRect(
        (left - margin).coerceAtLeast(0), (top - margin).coerceAtLeast(0),
        (right + margin + 1).coerceAtMost(width), (bottom + margin + 1).coerceAtMost(height),
    )
}

