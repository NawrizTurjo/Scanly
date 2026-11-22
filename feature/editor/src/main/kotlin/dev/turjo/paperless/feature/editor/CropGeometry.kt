package dev.turjo.paperless.feature.editor

import dev.turjo.paperless.core.model.Quad
import kotlin.math.hypot

internal data class CropPoint(val x: Float, val y: Float) {
    operator fun plus(other: CropPoint) = CropPoint(x + other.x, y + other.y)
    operator fun minus(other: CropPoint) = CropPoint(x - other.x, y - other.y)
    operator fun times(multiplier: Float) = CropPoint(x * multiplier, y * multiplier)
    fun dot(other: CropPoint) = x * other.x + y * other.y
    fun clamp(width: Float, height: Float) = CropPoint(x.coerceIn(0f, width), y.coerceIn(0f, height))
}

/** Fits the image into the view, keeping [inset] free on every side so handles on the image border stay touchable. */
internal class CropFit(
    viewWidth: Float,
    viewHeight: Float,
    imageWidth: Float,
    imageHeight: Float,
    inset: Float = 0f,
) {
    val scale = minOf((viewWidth - 2 * inset) / imageWidth, (viewHeight - 2 * inset) / imageHeight)
    val width = imageWidth * scale
    val height = imageHeight * scale
    val origin = CropPoint((viewWidth - width) / 2, (viewHeight - height) / 2)

    fun toView(point: CropPoint) = origin + point * scale
    fun toImage(point: CropPoint) = (point - origin) * (1f / scale)
}

internal fun Quad.points() = listOf(
    CropPoint(tlx, tly), CropPoint(trx, try_), CropPoint(brx, bry), CropPoint(blx, bly),
)

internal fun fullCrop(width: Float, height: Float) = Quad(0f, 0f, width, 0f, width, height, 0f, height)

internal fun Quad.handles(): List<CropPoint> {
    val corners = points()
    return corners + corners.indices.map { (corners[it] + corners[(it + 1) % 4]) * 0.5f }
}

/**
 * Moves a corner (0–3) or an edge midpoint (4–7). Points are clamped to the image, so a drag past the border pins the
 * handle to it. Detected corners on the photo border can sit a fraction outside it after scaling; rejecting those
 * froze every drag on close-up photos. Returns null only for a fold-over (non-convex) shape.
 */
internal fun Quad.moveHandle(handle: Int, delta: CropPoint, width: Float, height: Float): Quad? {
    val corners = points().map { it.clamp(width, height) }.toMutableList()
    if (handle < 4) {
        corners[handle] = (corners[handle] + delta).clamp(width, height)
    } else {
        val start = handle - 4
        val end = (start + 1) % 4
        val edge = corners[end] - corners[start]
        val length = hypot(edge.x, edge.y)
        if (length == 0f) return null
        val normal = CropPoint(-edge.y / length, edge.x / length)
        val movement = normal * delta.dot(normal)
        corners[start] = (corners[start] + movement).clamp(width, height)
        corners[end] = (corners[end] + movement).clamp(width, height)
    }
    val convex = corners.indices.all { i ->
        val first = corners[(i + 1) % 4] - corners[i]
        val second = corners[(i + 2) % 4] - corners[(i + 1) % 4]
        first.x * second.y - first.y * second.x > 0f
    }
    if (!convex) return null
    return Quad.fromArray(corners.flatMap { listOf(it.x, it.y) }.toFloatArray())
}

/** A split line across a crop: positions along the top edge (tl to tr) and the bottom edge (bl to br), 0..1. */
internal data class SplitLine(val top: Float, val bottom: Float) {
    companion object {
        const val MIN = 0.1f
        const val MAX = 0.9f
        val Middle = SplitLine(0.5f, 0.5f)
    }
}

internal fun Quad.splitPoints(line: SplitLine): Pair<CropPoint, CropPoint> {
    val (tl, tr, br, bl) = points()
    return (tl + (tr - tl) * line.top) to (bl + (br - bl) * line.bottom)
}

/** Left and right pages of a split, each a quad in the same tl, tr, br, bl order. */
internal fun Quad.splitHalves(line: SplitLine): Pair<Quad, Quad> {
    val (tl, tr, br, bl) = points()
    val (top, bottom) = splitPoints(line)
    fun quad(vararg p: CropPoint) = Quad.fromArray(p.flatMap { listOf(it.x, it.y) }.toFloatArray())
    return quad(tl, top, bottom, bl) to quad(top, tr, br, bottom)
}

/** Where [point] falls along the top (or bottom) edge, as a fraction kept away from the corners. */
internal fun Quad.edgeFraction(point: CropPoint, topEdge: Boolean): Float {
    val (tl, tr, br, bl) = points()
    val start = if (topEdge) tl else bl
    val edge = (if (topEdge) tr else br) - start
    val length = edge.dot(edge)
    if (length == 0f) return 0.5f
    return ((point - start).dot(edge) / length).coerceIn(SplitLine.MIN, SplitLine.MAX)
}

/** A fold found by detection (top and bottom points) as a split line, or the middle when it doesn't fit. */
internal fun Quad.splitLineFor(fold: FloatArray?): SplitLine {
    if (fold == null || fold.size < 4) return SplitLine.Middle
    return SplitLine(
        edgeFraction(CropPoint(fold[0], fold[1]), topEdge = true),
        edgeFraction(CropPoint(fold[2], fold[3]), topEdge = false),
    )
}
