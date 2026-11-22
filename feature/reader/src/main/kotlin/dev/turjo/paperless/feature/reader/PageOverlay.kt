package dev.turjo.paperless.feature.reader

import androidx.compose.ui.res.stringResource
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.PointF
import androidx.core.net.toUri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.turjo.paperless.core.model.PageLink
import dev.turjo.paperless.core.model.PageRect
import dev.turjo.paperless.core.pdf.PdfDocumentHandle
import kotlinx.coroutines.CancellationException
import kotlin.math.roundToInt

internal suspend fun <T> readerResult(block: suspend () -> T): T? = try {
    block()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    null
}

internal data class PageGeometry(val full: PageRect, val crop: PageRect) {
    val aspect get() = (crop.bottom - crop.top) / (crop.right - crop.left)
}

@Composable
internal fun PageOverlay(
    document: PdfDocumentHandle,
    page: Int,
    bitmap: android.graphics.Bitmap,
    geometry: PageGeometry,
    transform: PageTransform,
    search: SearchState,
    onLink: (PageLink) -> Unit,
    onDoubleTap: ((Offset) -> Unit)? = null,
) {
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    val pageMatches = remember(search.matches, page) {
        search.matches.withIndex().filter { it.value.page == page }
    }
    var links by remember(document, page) { mutableStateOf(emptyList<PageLink>()) }
    LaunchedEffect(document, page) { links = readerResult { document.links(page) }.orEmpty() }
    var start by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    var end by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    var selection by remember { mutableStateOf<Pair<String, List<PageRect>>?>(null) }
    LaunchedEffect(start, end) {
        val a = start
        val b = end
        if (a == null || b == null) {
            selection = null
        } else {
            val from = PointF(a.first, a.second)
            val to = PointF(b.first, b.second)
            val updated = readerResult { document.select(page, from, to) }
            if (updated != null) selection = updated
        }
    }
    val mapping by rememberUpdatedState(transform)
    val follow by rememberUpdatedState(onLink)
    val zoom by rememberUpdatedState(onDoubleTap)
    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize().pointerInput(document, page, onDoubleTap != null) {
            detectTapGestures(
                onLongPress = {
                    selection = null
                    val (x, y) = mapping.unmap(it.x, it.y)
                    start = x - 3f to y
                    end = x + 3f to y
                },
                onDoubleTap = if (onDoubleTap != null) ({ zoom?.invoke(it) }) else null,
                onTap = { point ->
                    selection = null
                    start = null
                    end = null
                    links.firstOrNull { link ->
                        link.bounds.any {
                            val bounds = mapping.map(it)
                            point.x in bounds.left..bounds.right && point.y in bounds.top..bounds.bottom
                        }
                    }?.let(follow)
                },
            )
        }) {
            val full = transform.map(geometry.full)
            drawImage(image,
                dstOffset = IntOffset(full.left.roundToInt(), full.top.roundToInt()),
                dstSize = IntSize((full.right - full.left).roundToInt(), (full.bottom - full.top).roundToInt()),
            )
            fun highlight(rect: PageRect, color: Color) {
                val bounds = transform.map(rect)
                drawRect(color, Offset(bounds.left, bounds.top), Size(bounds.right - bounds.left, bounds.bottom - bounds.top))
            }
            pageMatches.forEach { (index, match) ->
                match.rects.forEach {
                    highlight(it, Color.Yellow.copy(alpha = if (index == search.index) 0.62f else 0.32f))
                }
            }
            selection?.second?.forEach { highlight(it, Color(0x664888FF)) }
        }
        val rects = selection?.second.orEmpty()
        if (start != null && end != null && rects.isNotEmpty()) {
            val first = rects.first()
            val last = rects.last()
            SelectionHandle(start!!, first.left to first.bottom, transform, "Selection start") { start = it }
            SelectionHandle(end!!, last.right to last.bottom, transform, "Selection end") { end = it }
            val context = LocalContext.current
            Surface(Modifier.align(Alignment.TopCenter), shape = MaterialTheme.shapes.medium, tonalElevation = 4.dp) {
                Row {
                    TextButton({
                        context.getSystemService(ClipboardManager::class.java)
                            .setPrimaryClip(ClipData.newPlainText("PDF text", selection?.first.orEmpty()))
                    }) { Text(stringResource(R.string.reader_copy_text)) }
                    TextButton({
                        val share = Intent(Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(Intent.EXTRA_TEXT, selection?.first.orEmpty())
                        context.startActivity(Intent.createChooser(share, null))
                    }) { Text(stringResource(R.string.reader_share_text)) }
                }
            }
        }
    }
}

@Composable
private fun SelectionHandle(
    point: Pair<Float, Float>,
    anchor: Pair<Float, Float>,
    transform: PageTransform,
    label: String,
    update: (Pair<Float, Float>) -> Unit,
) {
    val mapping by rememberUpdatedState(transform)
    val current by rememberUpdatedState(point)
    val onUpdate by rememberUpdatedState(update)
    val size = 36.dp
    val radius = with(LocalDensity.current) { size.toPx() / 2f }
    val mapped = transform.map(PageRect(anchor.first, anchor.second, anchor.first, anchor.second))
    val color = MaterialTheme.colorScheme.primary
    Canvas(
        Modifier.offset { IntOffset((mapped.left - radius).roundToInt(), (mapped.top - radius).roundToInt()) }
            .size(size).then(Modifier.selectionLabel(label))
            .pointerInput(Unit) {
                var position = Offset.Zero
                detectDragGestures(
                    onDragStart = {
                        val (x, y) = current
                        val screen = mapping.map(PageRect(x, y, x, y))
                        position = Offset(screen.left, screen.top)
                    },
                ) { change, amount ->
                    change.consume()
                    position += amount
                    onUpdate(mapping.unmap(position.x, position.y))
                }
            },
    ) { drawCircle(color, 6.dp.toPx()) }
}

private fun Modifier.selectionLabel(label: String): Modifier = semantics { contentDescription = label }

@Composable
internal fun ExternalLinkDialog(uri: String, dismiss: () -> Unit, onError: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(stringResource(R.string.reader_open_browser)) },
        text = { Text(uri) },
        confirmButton = {
            TextButton({
                dismiss()
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, uri.toUri()).addCategory(Intent.CATEGORY_BROWSABLE))
                } catch (_: RuntimeException) {
                    onError()
                }
            }) { Text(stringResource(R.string.reader_open_link)) }
        },
        dismissButton = { TextButton(dismiss) { Text(stringResource(R.string.reader_cancel_link)) } },
    )
}

