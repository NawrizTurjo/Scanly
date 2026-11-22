package dev.turjo.paperless.core.imaging

import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.geometry.Geometry as CvGeometry
import org.opencv.imgproc.Imgproc
import java.util.Arrays
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.sqrt

private fun borderSide(a: Vec, b: Vec, width: Int, height: Int, margin: Double): Boolean =
    (a.x < margin && b.x < margin) || (a.y < margin && b.y < margin) ||
        (a.x > width - 1 - margin && b.x > width - 1 - margin) ||
        (a.y > height - 1 - margin && b.y > height - 1 - margin)

internal fun quadIou(a: FloatArray, b: FloatArray): Double = a.pointMat().useMat { first ->
    b.pointMat().useMat { second ->
        Mat().useMat { polygon ->
            val intersection = CvGeometry.intersectConvexConvex(first, second, polygon).toDouble()
            val union = CvGeometry.contourArea(first) + CvGeometry.contourArea(second) - intersection
            if (union > 0) intersection / union else 0.0
        }
    }
}

internal class PageScore(
    channels: List<Mat>,
    private val gradients: Gradients,
    private val threshold: Double,
    statsMax: Double = 500.0,
) {
    data class Candidate(
        val quad: FloatArray, val total: Double, val edge: Double, val area: Double, val support: List<Double?>,
    )

    private data class Stats(val page: Double, val rim: Double, val cover: Double, val bright: Double, val ref: Double)

    private val width = gradients.width
    private val height = gradients.height
    private val maxSide = maxOf(width, height)
    private val scale = statsMax / maxSide
    private val statsWidth = Math.rint(width * scale).toInt().coerceAtLeast(1)
    private val statsHeight = Math.rint(height * scale).toInt().coerceAtLeast(1)
    private val size = Size(statsWidth.toDouble(), statsHeight.toDouble())
    private val luma = FloatArray(statsWidth * statsHeight)
    private val paper = ByteArray(luma.size)
    private val median: Double
    private val selection = FloatArray(luma.size)

    init {
        val pixels = width * height
        val (l, a, b) = channels.map { mat -> ByteArray(pixels).also { mat.get(0, 0, it) } }
        median = Percentiles.ofHistogram(Percentiles.histogram(l), 50.0)
        newMat { channels[0].convertTo(it, CvType.CV_32F) }.useMat { fullLuma ->
            val local = newMat { Imgproc.blur(fullLuma, it, Size(21.0, 21.0)) }.useMat {
                FloatArray(pixels).also { values -> it.get(0, 0, values) }
            }
            newMat { Imgproc.resize(fullLuma, it, size, 0.0, 0.0, Imgproc.INTER_AREA) }.useMat {
                it.get(0, 0, luma)
            }
            val edges = ByteArray(pixels) { if (gradients.magnitude[it] >= threshold.toFloat()) 1 else 0 }
            Mat(height, width, CvType.CV_8UC1).useMat { mask ->
                mask.put(0, 0, edges)
                Mat.ones(3, 3, CvType.CV_8UC1).useMat { Imgproc.dilate(mask, mask, it) }
                mask.get(0, 0, edges)
                for (i in edges.indices) {
                    val ca = a[i].unsigned() - 128
                    val cb = b[i].unsigned() - 128
                    edges[i] = if (edges[i] == 0.toByte() && ca * ca + cb * cb < 24 * 24 &&
                        abs(l[i].unsigned() - local[i]) < 12f) 1 else 0
                }
                mask.put(0, 0, edges)
                newMat { Imgproc.resize(mask, it, size, 0.0, 0.0, Imgproc.INTER_NEAREST) }.useMat {
                    it.get(0, 0, paper)
                }
            }
        }
    }

    private fun mask(points: Array<Vec>, erode: Boolean): ByteArray =
        Mat.zeros(statsHeight, statsWidth, CvType.CV_8UC1).useMat { mask ->
            MatOfPoint(*points.map { Point(Math.rint(it.x), Math.rint(it.y)) }.toTypedArray()).useMat {
                Imgproc.fillConvexPoly(mask, it, Scalar(1.0))
            }
            if (erode) Mat.ones(3, 3, CvType.CV_8UC1).useMat { Imgproc.erode(mask, mask, it) }
            ByteArray(luma.size).also { mask.get(0, 0, it) }
        }

    private fun select(mask: ByteArray): Int {
        var count = 0
        for (i in luma.indices) if (mask[i] != 0.toByte()) selection[count++] = luma[i]
        return count
    }

    private fun reference(count: Int): Double {
        Arrays.sort(selection, 0, count)
        val rank = 0.7 * (count - 1)
        val lo = rank.toInt()
        return selection[lo] + (selection[ceil(rank).toInt()].toDouble() - selection[lo]) * (rank - lo)
    }

    private fun goodMap(ref: Double): ByteArray {
        val lo = (ref - 45).toFloat()
        val hi = (ref + 30).toFloat()
        return ByteArray(luma.size) { if (paper[it] != 0.toByte() && luma[it] > lo && luma[it] < hi) 1 else 0 }
    }

    private fun statsPoints(quad: FloatArray) = FloatArray(8) { quad[it] * scale.toFloat() }.vectors()

    private fun index(x: Double, y: Double): Int =
        Math.rint(y).toInt().coerceIn(0, statsHeight - 1) * statsWidth +
            Math.rint(x).toInt().coerceIn(0, statsWidth - 1)

    private fun pageStats(quad: FloatArray, support: List<Double?> = List(4) { 0.0 }): Stats {
        val points = statsPoints(quad)
        val selected = mask(points, erode = true)
        val count = select(selected)
        if (count < 100) return Stats(0.0, 0.0, 0.0, 0.0, 0.0)
        val ref = reference(count)
        val good = goodMap(ref)
        var hits = 0
        for (i in good.indices) if ((good[i].toInt() and selected[i].toInt()) != 0) hits++
        val page = hits.toDouble() / count

        val diag = hypot(statsWidth.toDouble(), statsHeight.toDouble())
        val centre = floatCentre(points)
        var rim = 1.0
        for (i in points.indices) {
            val a = points[i]
            val b = points[(i + 1) % 4]
            val d = floatDifference(b, a)
            var n = floatNormal(d)
            val midpoint = Vec(((a.x + b.x).toFloat() / 2).toDouble(), ((a.y + b.y).toFloat() / 2).toDouble())
            if (n.dot(centre - midpoint) < 0.0) n *= -1.0
            val offsets = if (support[i] == null) doubleArrayOf(0.004, 0.008, 0.012)
                else doubleArrayOf(0.015, 0.025, 0.035)
            var rimHits = 0
            for (j in 0 until 40) {
                val t = if (j == 39) 0.9 else 0.1 + j * (0.8 / 39)
                val base = a + d * t
                val deep = base + (centre - base) * 0.3
                val deepIndex = index(deep.x, deep.y)
                for (off in offsets) {
                    val nx = n.x.toFloat() * (off * diag).toFloat()
                    val ny = n.y.toFloat() * (off * diag).toFloat()
                    val sample = index(base.x + nx, base.y + ny)
                    if (good[sample] != 0.toByte() && abs(luma[sample] - luma[deepIndex]) < 25) rimHits++
                }
            }
            rim = minOf(rim, rimHits / 120.0)
        }

        val cover = Mat(statsHeight, statsWidth, CvType.CV_8UC1).useMat { components ->
            components.put(0, 0, good)
            Mat.ones(3, 3, CvType.CV_8UC1).useMat {
                Imgproc.morphologyEx(components, components, Imgproc.MORPH_OPEN, it)
            }
            Mat().useMat { labels ->
                val n = Imgproc.connectedComponents(components, labels, 4, CvType.CV_32S)
                val ids = IntArray(luma.size).also { labels.get(0, 0, it) }
                val inside = IntArray(n)
                val total = IntArray(n)
                for (i in ids.indices) {
                    total[ids[i]]++
                    inside[ids[i]] += selected[i].toInt()
                }
                var covered = 0
                var available = 0
                for (i in 1 until n) if (inside[i] >= 0.05 * count) {
                    covered += inside[i]
                    available += total[i]
                }
                covered.toDouble() / maxOf(1, available)
            }
        }
        return Stats(page, rim, cover, ((ref - median) / 25.0).coerceIn(0.0, 1.0), ref)
    }

    fun score(quad: FloatArray, aspectPrior: Float?): Candidate? {
        val q = quad.vectors()
        val area = quad.pointMat().useMat { CvGeometry.contourArea(it) }
        val fraction = area / (width.toDouble() * height)
        if (fraction !in 0.06..0.985 || !anglesOk(q)) return null
        val support = sideSupport(q)
        val real = support.filterNotNull()
        if (real.size < 2) return null
        val centre = floatCentre(q)
        val off = hypot(centre.x - width / 2.0, centre.y - height / 2.0) / (0.5 * hypot(width.toDouble(), height.toDouble()))
        val edge = 0.55 * real.min() + 0.25 * support.map { it ?: 0.7 }.average() +
            0.20 * minOf(1.0, fraction / 0.5) - 0.04 * (4 - real.size) +
            0.15 * (minOf(1.0, fraction / 0.3) - 0.5) - 0.10 * off
        val stats = pageStats(quad, support)
        val rim = 0.25 * (stats.rim - 0.8) + 0.6 * minOf(0.0, stats.rim - 0.85)
        var total = edge + 0.35 * (stats.page - 0.7) + rim + 0.30 * (stats.cover - 0.8) + 0.15 * (stats.bright - 1.0)
        if (aspectPrior != null) {
            val (w, h) = Geometry.sideLengths(quad)
            val aspect = maxOf(w, h) / minOf(w, h)
            total += 0.30 * (1.0 - minOf(1.0, abs(ln(aspect / aspectPrior)) / 0.25)) - rim
        }
        return Candidate(quad, total, edge, area, support)
    }

    private fun sideSupport(q: Array<Vec>): List<Double?> = q.indices.map { i ->
        val a = q[i]
        val b = q[(i + 1) % 4]
        if (borderSide(a, b, width, height, 0.012 * maxSide)) return@map null
        val d = floatDifference(b, a)
        val count = maxOf(20, floatLength(d).toInt())
        val n = floatNormal(d)
        val saturation = (SOFT_SUPPORT * threshold).toFloat()
        var sum = 0.0
        for (j in 0 until count) {
            val t = if (j == count - 1) 0.95 else 0.05 + j * ((0.95 - 0.05) / (count - 1))
            val p = a + d * t
            sum += minOf(1f, gradients.band(p.x, p.y, n.x, n.y) / saturation)
        }
        (sum / count).toFloat().toDouble()
    }

    fun preferEnclosing(best: Candidate, scored: List<Candidate>): Candidate {
        val base = mask(statsPoints(best.quad), erode = false)
        val count = select(base)
        if (count == 0) return best
        val good = goodMap(reference(count))
        val points = best.quad.points()
        val margin = 0.02 * maxSide
        var chosen = best
        for (candidate in scored) {
            if (candidate.support.any { it == null || it < 0.9 } || candidate.edge < best.edge ||
                candidate.area <= 1.08 * best.area || candidate.area > 2.0 * best.area ||
                candidate.area <= chosen.area) continue
            if (candidate.quad.vectors().any {
                    it.x < margin || it.y < margin || it.x > width - 1 - margin || it.y > height - 1 - margin
                }) continue
            val contains = candidate.quad.pointMat().useMat { polygon ->
                points.all { CvGeometry.pointPolygonTest(polygon, it, true) >= -3.0 }
            }
            if (!contains) continue
            val outer = mask(statsPoints(candidate.quad), erode = false)
            var ring = 0
            var hits = 0
            for (i in base.indices) if (base[i] == 0.toByte() && outer[i] != 0.toByte()) {
                ring++
                hits += good[i].toInt()
            }
            if (ring > 0 && hits.toDouble() / ring >= 0.3) chosen = candidate
        }
        return chosen
    }

    fun growToSpread(best: Candidate, scored: List<Candidate>, lines: List<DocumentLine>): Candidate {
        val refMine = pageStats(best.quad).ref
        for (candidate in scored) {
            val real = candidate.support.filterNotNull()
            if (real.average() < 0.6 || real.min() < 0.45 ||
                candidate.area !in (1.6 * best.area)..(2.6 * best.area)) continue
            val fold = fold(candidate.quad.vectors(), lines, 0.3, 0.7) ?: continue
            val first = quadIou(fold.first.orderedQuad(), best.quad)
            val second = quadIou(fold.second.orderedQuad(), best.quad)
            if (maxOf(first, second) < 0.8) continue
            val (mine, other) = if (first >= second) fold.first to fold.second else fold.second to fold.first
            val ratio = across(other, fold.axis) / maxOf(1e-6, across(mine, fold.axis))
            if (ratio !in 0.8..1.25 || !pageShaped(other)) continue
            val stats = pageStats(other.orderedQuad())
            if (stats.page >= 0.6 && stats.ref >= refMine - 25) return candidate
        }
        return best
    }
}

private fun floatDifference(a: Vec, b: Vec) =
    Vec((a.x.toFloat() - b.x.toFloat()).toDouble(), (a.y.toFloat() - b.y.toFloat()).toDouble())

private fun floatLength(d: Vec): Float {
    val x = d.x.toFloat()
    val y = d.y.toFloat()
    return sqrt(x * x + y * y)
}

private fun floatNormal(d: Vec): Vec {
    val length = (floatLength(d).toDouble() + 1e-9).toFloat()
    return Vec((-d.y.toFloat() / length).toDouble(), (d.x.toFloat() / length).toDouble())
}

private fun floatCentre(q: Array<Vec>): Vec {
    var x = 0f
    var y = 0f
    for (p in q) {
        x += p.x.toFloat()
        y += p.y.toFloat()
    }
    return Vec((x / 4).toDouble(), (y / 4).toDouble())
}

private data class Fold(val axis: Int, val first: Array<Vec>, val second: Array<Vec>)

private fun fold(q: Array<Vec>, lines: List<DocumentLine>, lo: Double, hi: Double): Fold? {
    val (tl, tr, br, bl) = q
    var best: Fold? = null
    var bestOffset = Double.POSITIVE_INFINITY
    val alignment = cos(Math.toRadians(10.0))
    for (axis in 0..1) {
        val s1 = if (axis == 0) bl - tl else tr - tl
        val s2 = if (axis == 0) br - tr else br - bl
        val fdir = (s1.unit() + s2.unit()).unit()
        val span = 0.5 * (s1.length() + s2.length())
        val start2 = if (axis == 0) bl else tr
        val d1 = if (axis == 0) tr - tl else bl - tl
        val d2 = br - start2
        val cross1 = DocumentLine(tl, d1, 0f)
        val cross2 = DocumentLine(start2, d2, 0f)
        for (line in lines) {
            if (abs(line.direction.dot(fdir)) < alignment || line.length < 0.6 * span) continue
            val x1 = line.intersect(cross1) ?: continue
            val x2 = line.intersect(cross2) ?: continue
            val u1 = (x1 - tl).dot(d1) / d1.dot(d1)
            val u2 = (x2 - start2).dot(d2) / d2.dot(d2)
            if (u1 <= lo || u1 >= hi || u2 <= lo || u2 >= hi || abs(u1 - u2) >= 0.15) continue
            val offset = abs(0.5 * (u1 + u2) - 0.5)
            if (offset >= bestOffset) continue
            bestOffset = offset
            best = if (axis == 0) Fold(axis, arrayOf(tl, x1, x2, bl), arrayOf(x1, tr, br, x2))
                else Fold(axis, arrayOf(tl, tr, x2, x1), arrayOf(x1, x2, br, bl))
        }
    }
    return best
}

private fun across(q: Array<Vec>, axis: Int): Double = if (axis == 0) {
    0.5 * ((q[1] - q[0]).length() + (q[2] - q[3]).length())
} else {
    0.5 * ((q[3] - q[0]).length() + (q[2] - q[1]).length())
}

private fun pageShaped(q: Array<Vec>): Boolean {
    val a = across(q, 0)
    val b = across(q, 1)
    return maxOf(a, b) / maxOf(1e-6, minOf(a, b)) in 1.1..2.0
}

internal fun splitSpread(q: Array<Vec>, lines: List<DocumentLine>, width: Int, height: Int): Array<Vec>? {
    val found = fold(q, lines, 0.12, 0.88) ?: return null
    val margin = 0.012 * maxOf(width, height)
    fun clipped(half: Array<Vec>, foldSide: Int): Boolean = half.indices.any {
        it != foldSide && borderSide(half[it], half[(it + 1) % 4], width, height, margin)
    }
    val firstCut = clipped(found.first, if (found.axis == 0) 1 else 2)
    val secondCut = clipped(found.second, if (found.axis == 0) 3 else 0)
    if (firstCut == secondCut) return null
    val (keep, cut) = if (firstCut) found.second to found.first else found.first to found.second
    return keep.takeIf { across(cut, found.axis) <= 0.8 * across(keep, found.axis) && pageShaped(keep) }
}
