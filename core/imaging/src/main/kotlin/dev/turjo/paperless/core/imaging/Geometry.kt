package dev.turjo.paperless.core.imaging

import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.round
import kotlin.math.sqrt

private val corners = 0..3

object Geometry {
    fun orderQuad(q: FloatArray): FloatArray {
        require(q.size == 8)
        val cx = corners.fold(0f) { sum, i -> sum + q[2 * i] } / 4
        val cy = corners.fold(0f) { sum, i -> sum + q[2 * i + 1] } / 4
        val indices = corners.sortedBy { atan2(q[2 * it + 1] - cy, q[2 * it] - cx) }
        val start = corners.minBy { q[2 * indices[it]] + q[2 * indices[it] + 1] }
        return FloatArray(8) { q[2 * indices[(start + it / 2) % 4] + it % 2] }
    }

    fun outputSize(q: FloatArray, snap: Boolean = true, snapTol: Double = 0.03): Size {
        val (width, height) = dimensions(q, snap, snapTol)
        return Size(width.toDouble(), height.toDouble())
    }

    internal fun dimensions(q: FloatArray, snap: Boolean, snapTol: Double = 0.03): Pair<Int, Int> {
        val ordered = orderQuad(q)
        val width = ((distance(ordered, 0, ordered, 1) + distance(ordered, 2, ordered, 3)) / 2.0)
            .coerceAtLeast(1.0)
        val height = ((distance(ordered, 0, ordered, 3) + distance(ordered, 1, ordered, 2)) / 2.0)
            .coerceAtLeast(1.0)
        val long = maxOf(width, height)
        var short = minOf(width, height)
        val ratio = long / short
        if (snap) {
            val target = listOf(297.0 / 210.0, 11.0 / 8.5)
                .firstOrNull { abs(ratio - it) / it <= snapTol }
            if (target != null) short = long / target
        }
        val roundedLong = round(long).toInt()
        val roundedShort = round(short).toInt()
        return if (width >= height) roundedLong to roundedShort else roundedShort to roundedLong
    }

    fun warp(src: Mat, q: FloatArray, size: Size? = null, snap: Boolean = true): Mat {
        val output = size ?: outputSize(q, snap)
        val x = output.width - 1
        val y = output.height - 1
        return orderQuad(q).pointMat().useMat { from ->
            MatOfPoint2f(Point(0.0, 0.0), Point(x, 0.0), Point(x, y), Point(0.0, y)).useMat { to ->
                org.opencv.geometry.Geometry.getPerspectiveTransform(from, to).useMat { transform ->
                    newMat { Imgproc.warpPerspective(src, it, transform, output, Imgproc.INTER_CUBIC, Core.BORDER_REPLICATE) }
                }
            }
        }
    }

    fun cornerError(found: FloatArray, truth: FloatArray): Pair<Double, Double> {
        require(truth.size == 8)
        val ordered = orderQuad(found)
        return corners.map { shift ->
            val distances = corners.map { distance(ordered, (it + shift) % 4, truth, it) }
            distances.average() to distances.max().toDouble()
        }.minBy { it.first }
    }

    private fun distance(a: FloatArray, i: Int, b: FloatArray, j: Int): Float {
        val x = a[2 * i] - b[2 * j]
        val y = a[2 * i + 1] - b[2 * j + 1]
        return sqrt(x * x + y * y)
    }
}

internal fun FloatArray.points(): Array<Point> = Array(size / 2) {
    Point(this[2 * it].toDouble(), this[2 * it + 1].toDouble())
}

internal fun FloatArray.pointMat() = MatOfPoint2f(*points())

