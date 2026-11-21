package dev.turjo.paperless.core.imaging

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfInt
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.geometry.Geometry as CvGeometry
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.round
import kotlin.math.sqrt

data class Detection(val quad: FloatArray?, val confidence: Float)

class QuadDetector {
    fun detect(bitmap: Bitmap, aspectPrior: Float? = null): Detection {
        OpenCv.ensureLoaded()
        val argb = if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap
            else requireNotNull(bitmap.copy(Bitmap.Config.ARGB_8888, false))
        try {
            return Mat().useMat { bgr ->
                Utils.bitmapToMat(argb, bgr)
                Imgproc.cvtColor(bgr, bgr, Imgproc.COLOR_RGBA2BGR)
                detect(bgr, aspectPrior = aspectPrior)
            }
        } finally {
            if (argb !== bitmap) argb.recycle()
        }
    }

    fun detect(bgr: Mat, refine: Boolean = true, aspectPrior: Float? = null): Detection {
        require(!bgr.empty() && bgr.type() == CvType.CV_8UC3)
        require(aspectPrior == null || (aspectPrior.isFinite() && aspectPrior > 0))
        val workingScale = imageScale(bgr, 1000)
        val scored = withResized(bgr, workingScale) { small -> scoreCandidates(small, aspectPrior) }
        val best = scored.maxByOrNull { it.score } ?: return Detection(null, 0f)
        if (best.score < MIN_SCORE) return Detection(null, best.score.toFloat())

        val width = round(bgr.cols() * workingScale).toInt().coerceAtLeast(1)
        val height = round(bgr.rows() * workingScale).toInt().coerceAtLeast(1)
        val chosen = if (aspectPrior == null) preferEnclosing(best, scored, width, height) else best
        var quad = Geometry.orderQuad(chosen.quad).mapScale(1 / workingScale)
        if (refine) {
            val refineScale = imageScale(bgr, 2000)
            quad = withResized(bgr, refineScale) { big -> refine(big, quad.mapScale(refineScale)) }
                .mapScale(1 / refineScale)
        }
        return Detection(Geometry.orderQuad(quad), minOf(1.0, best.score).toFloat())
    }

    internal data class Candidate(val quad: FloatArray, val score: Double, val area: Double)

    private fun scoreCandidates(small: Mat, aspectPrior: Float?): List<Candidate> {
        val width = small.cols()
        val height = small.rows()
        val gradients = colourGradient(small)
        return paperHull(small).useMat { paper ->
            val paperArea = if (paper.empty()) 0.0 else CvGeometry.contourArea(paper)
            Mat().useMat { intersection ->
                scoreCandidates(candidates(small), gradients, width, height) { quad, area ->
                    val paperAdjustment = if (paperArea == 0.0) 0.0 else {
                        val shared = Geometry.orderQuad(quad).pointMat().useMat {
                            CvGeometry.intersectConvexConvex(it, paper, intersection).toDouble()
                        }
                        0.3 * (shared / (area + paperArea - shared) - 0.75)
                    }
                    paperAdjustment + if (aspectPrior == null) 0.0 else {
                        val (w, h) = Geometry.sideLengths(quad)
                        val aspect = maxOf(w, h) / minOf(w, h)
                        0.15 * (1 - minOf(1.0, abs(ln(aspect / aspectPrior)) / 0.25))
                    }
                }
            }
        }
    }

    internal fun scoreCandidates(
        quads: List<FloatArray>,
        gradients: Gradients,
        width: Int,
        height: Int,
        percentileScratch: FloatArray = FloatArray(gradients.magnitude.size),
        adjustment: (FloatArray, Double) -> Double = { _, _ -> 0.0 },
    ): List<Candidate> {
        val threshold = maxOf(12.0, Percentiles.ofFloats(gradients.magnitude, 85.0, percentileScratch))
        return quads.mapNotNull { quad ->
            val area = quad.pointMat().useMat { CvGeometry.contourArea(it) }
            val fraction = area / (width.toDouble() * height)
            if (fraction !in 0.08..0.985 || !anglesOk(quad)) return@mapNotNull null
            val support = sideSupport(quad, gradients, width, height, threshold)
            val borderCount = support.count { it == null }
            if (borderCount > 2) return@mapNotNull null
            val filled = support.map { it ?: 1.0 }
            val score = 0.55 * filled.min() + 0.25 * filled.average() + 0.20 * minOf(1.0, fraction / 0.5)
            val adjusted = score - 0.06 * borderCount + adjustment(quad, area)
            if (adjusted >= 0.0) Candidate(quad, adjusted, area) else null
        }
    }

    private fun paperHull(small: Mat): Mat = newMat { hull ->
        val size = closingSize(small).toDouble()
        Mat().useMat { mask ->
            Imgproc.cvtColor(small, mask, Imgproc.COLOR_BGR2Lab)
            Core.extractChannel(mask, mask, 0)
            Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(size, size)).useMat {
                closeAndBlur(mask, mask, it)
            }
            Imgproc.threshold(mask, mask, 0.0, 255.0, Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU)
            Mat.ones(7, 7, CvType.CV_8UC1).useMat {
                Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_OPEN, it)
            }
            val contours = mutableListOf<MatOfPoint>()
            try {
                Mat().useMat { Imgproc.findContours(mask, contours, it, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE) }
                val largest = contours.maxByOrNull { CvGeometry.contourArea(it) }
                if (largest != null && CvGeometry.contourArea(largest) >= 0.3 * small.total()) {
                    MatOfInt().useMat { indices ->
                        CvGeometry.convexHull(largest, indices)
                        val points = largest.toArray()
                        MatOfPoint2f(*indices.toArray().map { points[it] }.toTypedArray()).useMat { it.copyTo(hull) }
                    }
                }
            } finally {
                contours.forEach(Mat::release)
            }
        }
    }

    private fun candidates(small: Mat): List<FloatArray> {
        val size = closingSize(small).toDouble()
        val result = mutableListOf<FloatArray>()
        Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(size, size)).useMat { closing ->
            Mat.ones(3, 3, CvType.CV_8UC1).useMat { dilation ->
                Mat.ones(5, 5, CvType.CV_8UC1).useMat { opening ->
                    Mat().useMat { converted ->
                        Mat().useMat { map ->
                            fun collect(source: Mat, channel: Int, invert: Boolean = false) {
                                Core.extractChannel(source, map, channel)
                                if (invert) Core.bitwise_not(map, map)
                                closeAndBlur(map, map, closing)
                                newMat { autoCanny(map, it) }.useMat {
                                    Imgproc.dilate(it, it, dilation)
                                    Mat().useMat { hierarchy -> collectContours(it, Imgproc.RETR_LIST, 6, result, hierarchy) }
                                }
                                Imgproc.threshold(map, map, 0.0, 255.0, Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU)
                                map.clone().useMat { inverse ->
                                    Core.bitwise_not(inverse, inverse)
                                    for (mask in listOf(map, inverse)) {
                                        Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_OPEN, opening)
                                        Mat().useMat { hierarchy -> collectContours(mask, Imgproc.RETR_EXTERNAL, 3, result, hierarchy) }
                                    }
                                }
                            }

                            Imgproc.cvtColor(small, converted, Imgproc.COLOR_BGR2Lab)
                            collect(converted, 0)
                            for (channel in 0..2) collect(small, channel)
                            Imgproc.cvtColor(small, converted, Imgproc.COLOR_BGR2HSV)
                            collect(converted, 1, invert = true)
                        }
                    }
                }
            }
        }
        return result
    }

    internal fun collectContours(
        mask: Mat, mode: Int, limit: Int, result: MutableList<FloatArray>, hierarchy: Mat,
    ) {
        val contours = mutableListOf<MatOfPoint>()
        try {
            Imgproc.findContours(mask, contours, hierarchy, mode, Imgproc.CHAIN_APPROX_SIMPLE)
            contours.map { it to CvGeometry.contourArea(it) }.sortedByDescending { it.second }
                .take(limit).mapNotNullTo(result) { contourQuad(it.first) }
        } finally {
            contours.forEach { it.release() }
        }
    }

    private fun contourQuad(contour: MatOfPoint): FloatArray? {
        val points = contour.toArray()
        MatOfPoint2f(*points).useMat { curve ->
            val perimeter = CvGeometry.arcLength(curve, true)
            MatOfPoint2f().useMat { approximation ->
                for (epsilon in doubleArrayOf(0.01, 0.02, 0.03, 0.04, 0.05, 0.07)) {
                    CvGeometry.approxPolyDP(curve, approximation, epsilon * perimeter, true)
                    if (approximation.total() == 4L) {
                        val quad = approximation.toArray()
                        if (isConvex(quad)) return quad.toQuad()
                    }
                }
            }
        }
        val hull = MatOfInt().useMat {
            CvGeometry.convexHull(contour, it)
            it.toArray().map { index -> points[index] }.toMutableList()
        }
        if (hull.size < 4) return null
        while (hull.size > 4) {
            val remove = hull.indices.minBy { i ->
                val a = hull[(i + hull.size - 1) % hull.size]
                val b = hull[i]
                val c = hull[(i + 1) % hull.size]
                abs((b.x - a.x) * (c.y - a.y) - (c.x - a.x) * (b.y - a.y))
            }
            hull.removeAt(remove)
        }
        val quad = hull.toTypedArray()
        return if (isConvex(quad)) quad.toQuad() else null
    }

    private fun isConvex(points: Array<Point>): Boolean =
        MatOfPoint(*points).useMat { CvGeometry.isContourConvex(it) }

    private fun anglesOk(quad: FloatArray): Boolean {
        val q = Geometry.orderQuad(quad).points()
        return q.indices.all { i ->
            val a = q[(i + 3) % 4]
            val b = q[i]
            val c = q[(i + 1) % 4]
            val x1 = a.x - b.x
            val y1 = a.y - b.y
            val x2 = c.x - b.x
            val y2 = c.y - b.y
            val cosine = (x1 * x2 + y1 * y2) / (hypot(x1, y1) * hypot(x2, y2) + 1e-9)
            Math.toDegrees(acos(cosine.coerceIn(-1.0, 1.0))) in 40.0..140.0
        }
    }

    private fun sideSupport(quad: FloatArray, gradients: Gradients, width: Int, height: Int, threshold: Double): Array<Double?> {
        val q = Geometry.orderQuad(quad).points()
        val margin = 0.012 * maxOf(width, height)
        return Array(4) { i ->
            val a = q[i]
            val b = q[(i + 1) % 4]
            if ((a.x < margin && b.x < margin) || (a.y < margin && b.y < margin) ||
                (a.x > width - 1 - margin && b.x > width - 1 - margin) ||
                (a.y > height - 1 - margin && b.y > height - 1 - margin)
            ) return@Array null
            val length = hypot(b.x - a.x, b.y - a.y) + 1e-9
            val nx = (-(b.y - a.y) / length).toFloat()
            val ny = ((b.x - a.x) / length).toFloat()
            val count = maxOf(20, (length / 3).toInt())
            val step = (0.95 - 0.05) / (count - 1)
            var hits = 0
            for (j in 0 until count) {
                val t = if (j == count - 1) 0.95 else 0.05 + j * step
                val x = round(a.x * (1 - t) + b.x * t).toInt()
                val y = round(a.y * (1 - t) + b.y * t).toInt()
                sample@ for (row in maxOf(0, y - 2)..minOf(height - 1, y + 2)) {
                    for (col in maxOf(0, x - 2)..minOf(width - 1, x + 2)) {
                        val pixel = row * width + col
                        for (channel in gradients.x.indices) {
                            if (abs(gradients.x[channel][pixel] * nx + gradients.y[channel][pixel] * ny) >= threshold) {
                                hits++
                                break@sample
                            }
                        }
                    }
                }
            }
            hits.toDouble() / count
        }
    }

    private fun colourGradient(src: Mat): Gradients {
        val gradients = Gradients(3, src.rows() * src.cols())
        val size = closingSize(src).toDouble()
        Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(size, size)).useMat { kernel ->
            newMat {
                Imgproc.GaussianBlur(src, it, Size(3.0, 3.0), 0.0)
                Imgproc.cvtColor(it, it, Imgproc.COLOR_BGR2Lab)
            }.useMat { lab ->
                Mat().useMat { channel ->
                    Mat().useMat { gx ->
                        Mat().useMat { gy ->
                            for (i in 0..2) {
                                Core.extractChannel(lab, channel, i)
                                Imgproc.morphologyEx(channel, channel, Imgproc.MORPH_CLOSE, kernel, Point(-1.0, -1.0), 2)
                                val gain = if (i == 0) 1.0 else 2.0
                                Imgproc.Sobel(channel, gx, CvType.CV_32F, 1, 0, 3, gain)
                                Imgproc.Sobel(channel, gy, CvType.CV_32F, 0, 1, 3, gain)
                                gx.get(0, 0, gradients.x[i])
                                gy.get(0, 0, gradients.y[i])
                            }
                        }
                    }
                }
            }
        }
        for (i in gradients.magnitude.indices) {
            var maxSquared = 0f
            for (ch in gradients.x.indices) {
                val dx = gradients.x[ch][i]
                val dy = gradients.y[ch][i]
                maxSquared = maxOf(maxSquared, dx * dx + dy * dy)
            }
            gradients.magnitude[i] = sqrt(maxSquared)
        }
        return gradients
    }

    private fun magnitude(gray: Mat): Mat =
        Mat().useMat { gx ->
            Mat().useMat { gy -> newMat { lumaGradient(gray, it, gx, gy) } }
        }

    internal fun preferEnclosing(best: Candidate, scored: List<Candidate>, width: Int, height: Int): Candidate {
        var chosen = best
        val margin = 0.01 * maxOf(width, height)
        val points = best.quad.points()
        for (candidate in scored) {
            if (candidate.score < 0.7 * best.score || candidate.area < 1.08 * chosen.area) continue
            if (candidate.quad.points().any {
                    it.x < margin || it.y < margin || it.x > width - 1 - margin || it.y > height - 1 - margin
                }) continue
            val contains = candidate.quad.pointMat().useMat { polygon ->
                points.all { CvGeometry.pointPolygonTest(polygon, it, true) >= -3.0 }
            }
            if (contains) chosen = candidate
        }
        return chosen
    }

    private fun refine(img: Mat, quad: FloatArray): FloatArray {
        val width = img.cols()
        val height = img.rows()
        val mag = newMat { gray ->
            Imgproc.cvtColor(img, gray, Imgproc.COLOR_BGR2GRAY)
            Imgproc.GaussianBlur(gray, gray, Size(3.0, 3.0), 0.0)
        }.useMat { gray ->
            magnitude(gray).useMat { gradient ->
                FloatArray(width * height).also { gradient.get(0, 0, it) }
            }
        }
        val q = Geometry.orderQuad(quad)
        val lines = ArrayList<FloatArray>(4)
        for (i in 0..3) {
            val line = fitSide(q, i, mag, width, height) ?: return quad
            lines.add(line)
        }
        val result = FloatArray(8)
        for (i in 0..3) {
            val (vx1, vy1, x1, y1) = lines[(i + 3) % 4]
            val (vx2, vy2, x2, y2) = lines[i]
            val determinant = vx2.toDouble() * vy1 - vx1.toDouble() * vy2
            if (abs(determinant) < 1e-6) return quad
            val t = (((y2 - y1).toDouble() * vx2 - (x2 - x1).toDouble() * vy2) / determinant).toFloat()
            result[2 * i] = x1 + vx1 * t
            result[2 * i + 1] = y1 + vy1 * t
            if (hypot((result[2 * i] - q[2 * i]).toDouble(), (result[2 * i + 1] - q[2 * i + 1]).toDouble())
                > 0.03 * hypot(width.toDouble(), height.toDouble())) return q
        }
        return result
    }

    private fun fitSide(q: FloatArray, i: Int, mag: FloatArray, width: Int, height: Int): FloatArray? {
        val start = 2 * i
        val next = (start + 2) % 8
        val dx = q[next] - q[start]
        val dy = q[next + 1] - q[start + 1]
        val length = sqrt(dx * dx + dy * dy)
        if (length < 10) return null
        val nx = -dy / length
        val ny = dx / length
        val count = maxOf(30, (length / 4).toInt())
        val step = (0.92 - 0.08) / (count - 1)
        val points = ArrayList<Point>(count)
        val samples = FloatArray(13)
        for (j in 0 until count) {
            val t = (if (j == count - 1) 0.92 else 0.08 + j * step).toFloat()
            val px = q[start] + dx * t
            val py = q[start + 1] + dy * t
            var peak = 0
            for (k in samples.indices) {
                val x = round(px + nx * (k - 6)).toInt().coerceIn(0, width - 1)
                val y = round(py + ny * (k - 6)).toInt().coerceIn(0, height - 1)
                samples[k] = mag[y * width + x]
                if (samples[k] > samples[peak]) peak = k
            }
            if (samples[peak] <= 0) continue
            var offset = (peak - 6).toFloat()
            if (peak in 1..11) {
                val denominator = samples[peak - 1] - 2 * samples[peak] + samples[peak + 1]
                if (abs(denominator) > 1e-6) {
                    offset += (0.5 * (samples[peak - 1] - samples[peak + 1]) / denominator).toFloat()
                }
            }
            points.add(Point((px + nx * offset).toDouble(), (py + ny * offset).toDouble()))
        }
        if (points.size < 8) return null
        return MatOfPoint2f(*points.toTypedArray()).useMat { samplesMat ->
            newMat { CvGeometry.fitLine(samplesMat, it, CvGeometry.DIST_HUBER, 0.0, 0.01, 0.01) }.useMat {
                FloatArray(4).also { line -> it.get(0, 0, line) }
            }
        }
    }
}

private fun Array<Point>.toQuad(): FloatArray = FloatArray(8) {
    (if (it % 2 == 0) this[it / 2].x else this[it / 2].y).toFloat()
}

internal fun FloatArray.mapScale(scale: Double): FloatArray = FloatArray(size) { this[it] * scale.toFloat() }

internal fun imageScale(m: Mat, maxSide: Int): Double = minOf(1.0, maxSide.toDouble() / maxOf(m.cols(), m.rows()))

internal inline fun <T> withResized(m: Mat, scale: Double, block: (Mat) -> T): T {
    if (scale >= 1) return block(m)
    val size = Size(maxOf(1.0, round(m.cols() * scale)), maxOf(1.0, round(m.rows() * scale)))
    return newMat { Imgproc.resize(m, it, size, 0.0, 0.0, Imgproc.INTER_AREA) }.useMat(block)
}


internal fun closingSize(gray: Mat): Int =
    maxOf(5, round(maxOf(gray.rows(), gray.cols()) / 90.0).toInt() or 1)

internal fun closeAndBlur(gray: Mat, out: Mat, kernel: Mat) {
    Imgproc.morphologyEx(gray, out, Imgproc.MORPH_CLOSE, kernel, Point(-1.0, -1.0), 2)
    Imgproc.GaussianBlur(out, out, Size(5.0, 5.0), 0.0)
}

internal fun autoCanny(gray: Mat, out: Mat) {
    val median = Percentiles.ofHistogram(histogram(gray), 50.0)
    val low = maxOf(5.0, (1.0 - 0.33) * median * 0.5).toInt()
    val high = maxOf(low + 10.0, minOf(255.0, (1.0 + 0.33) * median * 0.5)).toInt()
    Imgproc.Canny(gray, out, low.toDouble(), high.toDouble(), 3, true)
}

internal fun lumaGradient(gray: Mat, out: Mat, gx: Mat, gy: Mat) {
    Imgproc.Sobel(gray, gx, CvType.CV_32F, 1, 0, 3)
    Imgproc.Sobel(gray, gy, CvType.CV_32F, 0, 1, 3)
    Core.magnitude(gx, gy, out)
}

internal const val MIN_SCORE = 0.35

internal class Gradients(channels: Int, pixels: Int) {
    val x = Array(channels) { FloatArray(pixels) }
    val y = Array(channels) { FloatArray(pixels) }
    val magnitude = FloatArray(pixels)
}
