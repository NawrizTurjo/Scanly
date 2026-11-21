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

internal class CropFit(viewWidth: Float, viewHeight: Float, imageWidth: Float, imageHeight: Float) {
    val scale = minOf(viewWidth / imageWidth, viewHeight / imageHeight)
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

internal fun Quad.moveHandle(handle: Int, delta: CropPoint, width: Float, height: Float): Quad? {
    val corners = points().toMutableList()
    if (handle < 4) {
        corners[handle] += delta
    } else {
        val start = handle - 4
        val end = (start + 1) % 4
        val edge = corners[end] - corners[start]
        val length = hypot(edge.x, edge.y)
        if (length == 0f) return null
        val normal = CropPoint(-edge.y / length, edge.x / length)
        val movement = normal * delta.dot(normal)
        corners[start] += movement
        corners[end] += movement
    }
    if (corners.any { it != it.clamp(width, height) }) return null
    val convex = corners.indices.all { i ->
        val first = corners[(i + 1) % 4] - corners[i]
        val second = corners[(i + 2) % 4] - corners[(i + 1) % 4]
        first.x * second.y - first.y * second.x > 0f
    }
    if (!convex) return null
    return Quad.fromArray(corners.flatMap { listOf(it.x, it.y) }.toFloatArray())
}
