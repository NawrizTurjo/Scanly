package dev.turjo.paperless.core.imaging

import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

internal const val SOFT_SUPPORT = 1.6

internal data class Vec(val x: Double, val y: Double) {
    operator fun plus(other: Vec) = Vec(x + other.x, y + other.y)
    operator fun minus(other: Vec) = Vec(x - other.x, y - other.y)
    operator fun times(scale: Double) = Vec(x * scale, y * scale)
    fun dot(other: Vec) = x * other.x + y * other.y
    fun cross(other: Vec) = x * other.y - y * other.x
    fun length() = sqrt(dot(this))
    fun unit() = this * (1.0 / length())
    fun normal() = Vec(-y, x)
}

internal fun FloatArray.vectors() = Array(4) { Vec(this[2 * it].toDouble(), this[2 * it + 1].toDouble()) }

private fun Array<Vec>.toQuad() = FloatArray(8) {
    val point = this[it / 2]
    (if (it % 2 == 0) point.x else point.y).toFloat()
}

internal fun Array<Vec>.orderedQuad() = Geometry.orderQuad(toQuad())

internal fun anglesOk(q: Array<Vec>): Boolean = q.indices.all { i ->
    val a = q[(i + 3) % 4] - q[i]
    val b = q[(i + 1) % 4] - q[i]
    val cosine = a.dot(b) / (a.length() * b.length() + 1e-9)
    Math.toDegrees(acos(cosine.coerceIn(-1.0, 1.0))) in 45.0..135.0
}

internal class Gradients(channels: List<Mat>, val width: Int, val height: Int) {
    private val x = Array(channels.size) { FloatArray(width * height) }
    private val y = Array(channels.size) { FloatArray(width * height) }
    val magnitude = FloatArray(width * height)

    init {
        Mat().useMat { sobel ->
            for ((i, channel) in channels.withIndex()) {
                val gain = if (i == 0) 1.0 else 2.0
                Imgproc.Sobel(channel, sobel, CvType.CV_32F, 1, 0, 3, gain)
                sobel.get(0, 0, x[i])
                Imgproc.Sobel(channel, sobel, CvType.CV_32F, 0, 1, 3, gain)
                sobel.get(0, 0, y[i])
            }
        }
        for (i in magnitude.indices) {
            var squared = 0f
            for (c in x.indices) squared = maxOf(squared, x[c][i] * x[c][i] + y[c][i] * y[c][i])
            magnitude[i] = sqrt(squared)
        }
    }

    fun band(x: Double, y: Double, nx: Double, ny: Double): Float {
        var best = 0f
        val fx = nx.toFloat()
        val fy = ny.toFloat()
        for (offset in -2..2) {
            val col = Math.rint(x + offset * nx).toInt().coerceIn(0, width - 1)
            val row = Math.rint(y + offset * ny).toInt().coerceIn(0, height - 1)
            val index = row * width + col
            for (c in this.x.indices) {
                best = maxOf(best, abs(this.x[c][index] * fx + this.y[c][index] * fy))
            }
        }
        return best
    }
}

internal class DocumentLine(val point: Vec, direction: Vec, val length: Float, val frame: Boolean = false) {
    val direction = direction.unit()
    val horizontal = abs(this.direction.x) >= abs(this.direction.y)
    private var start = 0.0
    private var prefix = FloatArray(2)

    fun intersect(other: DocumentLine): Vec? {
        val det = direction.cross(other.direction)
        if (abs(det) < 1e-9) return null
        return point + direction * ((other.point - point).cross(other.direction) / det)
    }

    fun buildProfile(gradients: Gradients, threshold: Double) {
        var lo = -1e9
        var hi = 1e9
        fun constrain(p: Double, d: Double, size: Int) {
            if (abs(d) < 1e-9) {
                if (p !in 0.0..(size - 1.0)) {
                    lo = 1.0
                    hi = 0.0
                }
            } else {
                val a = -p / d
                val b = (size - 1 - p) / d
                lo = maxOf(lo, minOf(a, b))
                hi = minOf(hi, maxOf(a, b))
            }
        }
        constrain(point.x, direction.x, gradients.width)
        constrain(point.y, direction.y, gradients.height)
        if (hi <= lo) return
        start = lo
        prefix = FloatArray(ceil(hi - lo).toInt() + 1)
        val step = (lo + 1.0) - lo
        val normal = direction.normal()
        val saturation = (SOFT_SUPPORT * threshold).toFloat()
        for (i in 0 until prefix.lastIndex) {
            val sample = point + direction * (lo + i * step)
            val support = gradients.band(sample.x, sample.y, normal.x, normal.y)
            prefix[i + 1] = prefix[i] + minOf(1f, support / saturation)
        }
    }

    fun support(a: Vec, b: Vec): Double {
        if (frame) return 1.0
        val ta = (a - point).dot(direction) - start
        val tb = (b - point).dot(direction) - start
        val span = abs(tb - ta)
        val last = prefix.size - 2
        val ia = (minOf(ta, tb) + 0.05 * span).toInt().coerceIn(0, last)
        val ib = (maxOf(ta, tb) - 0.05 * span).toInt().coerceIn(0, last)
        return if (ib <= ia) 0.0 else (prefix[ib].toDouble() - prefix[ia]) / (ib - ia)
    }
}

internal object LineCandidates {
    data class Result(val quads: List<FloatArray>, val merged: List<DocumentLine>)

    private data class Segment(val x0: Float, val y0: Float, val x1: Float, val y1: Float) {
        val length = hypot((x1 - x0).toDouble(), (y1 - y0).toDouble()).toFloat()
        val centre = Vec((x0 + x1).toDouble() / 2, (y0 + y1).toDouble() / 2)
        val angle = atan2(y1 - y0, x1 - x0).toDouble().let { if (it < 0) it + Math.PI else it }
    }

    private class Cluster(val angle: Double, val offset: Double, val segments: MutableList<Segment>) {
        val normal = Vec(-sin(angle), cos(angle))

        fun fit(): DocumentLine {
            var weight = 0f
            var sumX = 0f
            var sumY = 0f
            var length = 0f
            for (s in segments) {
                sumX += s.x0 * s.length
                sumX += s.x1 * s.length
                sumY += s.y0 * s.length
                sumY += s.y1 * s.length
                weight += s.length
                weight += s.length
                length += s.length
            }
            val mx = sumX / weight
            val my = sumY / weight
            var xx = 0f
            var xy = 0f
            var yy = 0f
            fun accumulate(x: Float, y: Float, w: Float) {
                val dx = x - mx
                val dy = y - my
                xx += (dx * w) * dx
                xy += (dx * w) * dy
                yy += (dy * w) * dy
            }
            for (s in segments) {
                accumulate(s.x0, s.y0, s.length)
                accumulate(s.x1, s.y1, s.length)
            }
            val theta = 0.5 * atan2(2.0 * xy, xx.toDouble() - yy)
            // The profile origin depends on direction sign. Match the principal eigenvector from eigh.
            val sign = if ((xx > yy && xy != 0f) || (xx <= yy && xy < 0f)) -1.0 else 1.0
            val direction = if (xy == 0f && xx <= yy) Vec(0.0, 1.0)
                else Vec(sign * cos(theta).toFloat(), sign * sin(theta).toFloat())
            return DocumentLine(Vec(mx.toDouble(), my.toDouble()), direction, length)
        }
    }

    fun mergedLines(maps: List<Mat>, diagonal: Double): List<DocumentLine> {
        val detector = Imgproc.createLineSegmentDetector()
        val segments = mutableListOf<Segment>()
        try {
            Mat().useMat { lines ->
                for (map in maps) {
                    detector.detect(map, lines)
                    if (lines.empty()) continue
                    val coordinates = FloatArray(lines.total().toInt() * 4)
                    lines.get(0, 0, coordinates)
                    for (i in coordinates.indices step 4) {
                        val segment = Segment(coordinates[i], coordinates[i + 1], coordinates[i + 2], coordinates[i + 3])
                        if (segment.length >= 0.05 * diagonal) segments.add(segment)
                    }
                }
            }
        } finally {
            detector.clear()
        }
        val clusters = mutableListOf<Cluster>()
        for (segment in segments.sortedByDescending { it.length }) {
            val match = clusters.firstOrNull {
                val delta = abs(segment.angle - it.angle)
                minOf(delta, Math.PI - delta) <= Math.toRadians(2.5) &&
                    abs(it.normal.dot(segment.centre) - it.offset) < 0.010 * diagonal
            }
            if (match != null) {
                match.segments.add(segment)
            } else {
                val normal = Vec(-sin(segment.angle), cos(segment.angle))
                clusters.add(Cluster(segment.angle, normal.dot(segment.centre), mutableListOf(segment)))
            }
        }
        return clusters.map { it.fit() }
    }

    private fun convexArea(quad: FloatArray): Double {
        var orientation = 0
        var area = 0.0
        for (i in 0..3) {
            val a = 2 * i
            val b = (a + 2) % 8
            val c = (a + 4) % 8
            val cross = (quad[b] - quad[a]) * (quad[c + 1] - quad[b + 1]) -
                (quad[b + 1] - quad[a + 1]) * (quad[c] - quad[b])
            if (cross == 0f) return 0.0
            val sign = if (cross > 0f) 1 else -1
            if (orientation != 0 && orientation != sign) return 0.0
            orientation = sign
            area += quad[a].toDouble() * quad[b + 1] - quad[a + 1].toDouble() * quad[b]
        }
        return 0.5 * abs(area)
    }

    fun find(maps: List<Mat>, gradients: Gradients, threshold: Double, keep: Int = 60): Result {
        val width = gradients.width
        val height = gradients.height
        val merged = mergedLines(maps, hypot(width.toDouble(), height.toDouble()))
        val horizontal = merged.filter { it.horizontal }.sortedByDescending { it.length }.take(14) + listOf(
            DocumentLine(Vec(0.0, 0.0), Vec(1.0, 0.0), 0f, true),
            DocumentLine(Vec(0.0, height - 1.0), Vec(1.0, 0.0), 0f, true),
        )
        val vertical = merged.filterNot { it.horizontal }.sortedByDescending { it.length }.take(14) + listOf(
            DocumentLine(Vec(0.0, 0.0), Vec(0.0, 1.0), 0f, true),
            DocumentLine(Vec(width - 1.0, 0.0), Vec(0.0, 1.0), 0f, true),
        )
        for (line in horizontal + vertical) {
            if (!line.frame) line.buildProfile(gradients, threshold)
        }
        val margin = 0.02 * maxOf(width, height)
        val intersections = horizontal.map { h ->
            vertical.map { v ->
                h.intersect(v)?.takeIf {
                    it.x in -margin..(width - 1 + margin) && it.y in -margin..(height - 1 + margin)
                }?.let { Vec(it.x.coerceIn(0.0, width - 1.0), it.y.coerceIn(0.0, height - 1.0)) }
            }
        }
        val candidates = mutableListOf<Pair<Double, FloatArray>>()
        for (i in horizontal.indices) for (j in i + 1 until horizontal.size) {
            for (k in vertical.indices) for (l in k + 1 until vertical.size) {
                val sides = arrayOf(horizontal[i], vertical[l], horizontal[j], vertical[k])
                val clipped = sides.count { it.frame }
                if (clipped > 2) continue
                val a = intersections[i][k] ?: continue
                val b = intersections[i][l] ?: continue
                val c = intersections[j][l] ?: continue
                val d = intersections[j][k] ?: continue
                val points = arrayOf(a, b, c, d)
                val quad = points.toQuad()
                val fraction = convexArea(quad) / (width.toDouble() * height)
                if (fraction !in 0.06..0.985 || !anglesOk(points)) continue
                val support = sides.indices.map { sides[it].support(points[it], points[(it + 1) % 4]) }
                val score = 0.55 * support.min() + 0.25 * support.average() +
                    0.20 * minOf(1.0, fraction / 0.5) - 0.04 * clipped
                candidates.add(score to Geometry.orderQuad(quad))
            }
        }
        return Result(candidates.sortedByDescending { it.first }.take(keep).map { it.second }, merged)
    }
}
