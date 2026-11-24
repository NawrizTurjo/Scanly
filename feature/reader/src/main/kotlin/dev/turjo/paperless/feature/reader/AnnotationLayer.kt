package dev.turjo.paperless.feature.reader

import dev.turjo.paperless.core.data.SavedSignature
import android.graphics.PointF
import dev.turjo.paperless.core.model.Annotation
import dev.turjo.paperless.core.model.AnnotationType
import dev.turjo.paperless.core.model.PageRect
import kotlin.math.ceil
import kotlin.math.hypot

internal enum class AnnotationTool(val label: String, val type: AnnotationType? = null) {
    MOVE("Navigate"), PEN("Pen", AnnotationType.INK), HIGHLIGHTER("Highlighter", AnnotationType.HIGHLIGHT),
    UNDERLINE("Underline", AnnotationType.UNDERLINE), STRIKE("Strike", AnnotationType.STRIKE),
    NOTE("Text note", AnnotationType.NOTE), ERASER("Eraser"), SIGNATURE(""),
}

internal data class AnnotationLayer(
    val saved: List<Annotation>,
    val marks: List<Annotation> = saved,
    val undo: List<List<Annotation>> = emptyList(),
    val redo: List<List<Annotation>> = emptyList(),
    val editing: Boolean = false,
    val tool: AnnotationTool = AnnotationTool.PEN,
    val color: Int = 0xFF215DC8.toInt(),
    val width: Float = 3f,
    val saving: Boolean = false,
    val pendingSignature: SavedSignature? = null,
) {
    val dirty get() = marks != saved

    fun change(next: List<Annotation>): AnnotationLayer =
        if (next == marks) this else copy(marks = next, undo = undo.takeLast(49) + listOf(marks), redo = emptyList())

    fun undo(): AnnotationLayer = undo.lastOrNull()?.let {
        copy(marks = it, undo = undo.dropLast(1), redo = redo + listOf(marks))
    } ?: this

    fun redo(): AnnotationLayer = redo.lastOrNull()?.let {
        copy(marks = it, redo = redo.dropLast(1), undo = undo + listOf(marks))
    } ?: this

    fun discard() = copy(marks = saved, undo = emptyList(), redo = emptyList(), editing = false, pendingSignature = null)
}

internal data class AnnotationPrompt(val tabId: String, val closing: Boolean)

internal fun Annotation.touches(point: PointF, radius: Float): Boolean {
    fun PageRect.contains() = point.x in (left - radius)..(right + radius) &&
        point.y in (top - radius)..(bottom + radius)
    if (rects.any { it.contains() }) return true
    val first = points.firstOrNull() ?: return false
    if (type == AnnotationType.NOTE) return PageRect(first.x, first.y, first.x + 9, first.y + 9).contains()
    val tolerance = radius + width / 2
    if (hypot(point.x - first.x, point.y - first.y) <= tolerance) return true
    return points.zipWithNext().any { (a, b) ->
        val dx = b.x - a.x
        val dy = b.y - a.y
        val length = dx * dx + dy * dy
        val fraction = if (length == 0f) 0f else
            (((point.x - a.x) * dx + (point.y - a.y) * dy) / length).coerceIn(0f, 1f)
        hypot(point.x - a.x - dx * fraction, point.y - a.y - dy * fraction) <= tolerance
    }
}


internal fun eraserSamples(points: List<PointF>): List<PointF> = buildList {
    add(points.first())
    for ((a, b) in points.zipWithNext()) {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val count = ceil(hypot(dx, dy) / 5f).toInt()
        for (index in 1 until count) {
            val fraction = index.toFloat() / count
            add(PointF(a.x + fraction * dx, a.y + fraction * dy))
        }
        add(b)
    }
}
