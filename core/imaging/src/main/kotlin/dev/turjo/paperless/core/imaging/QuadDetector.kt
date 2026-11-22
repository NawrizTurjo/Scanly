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
import kotlin.math.hypot
import kotlin.math.round
import kotlin.math.sqrt

internal const val LIVE_WORK_MAX = 480
private const val LIVE_TOP_LINE = 30
private const val LIVE_STATS_MAX = 240.0
private const val MIN_SCORE = 0.45

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
        val result = withResized(bgr, workingScale) { detectWorking(it, aspectPrior) }
        var quad = result.quad?.divideByScale(workingScale) ?: return result
        if (refine) {
            val refineScale = imageScale(bgr, 2000)
            quad = withResized(bgr, refineScale) { big ->
                refine(big, FloatArray(8) { quad[it] * refineScale.toFloat() })
            }.divideByScale(refineScale)
        }
        return Detection(Geometry.orderQuad(quad), result.confidence)
    }

    internal fun detectLive(gray: Mat): Detection =
        newMat { Imgproc.cvtColor(gray, it, Imgproc.COLOR_GRAY2BGR) }.useMat { bgr ->
            withResized(bgr, imageScale(bgr, LIVE_WORK_MAX)) { detectWorking(it, null, live = true) }
        }

    private fun detectWorking(small: Mat, aspectPrior: Float?, live: Boolean = false): Detection {
        val width = small.cols()
        val height = small.rows()
        val channels = List(3) { Mat() }
        try {
            val k = closingSize(small)
            newMat {
                Imgproc.GaussianBlur(small, it, Size(3.0, 3.0), 0.0)
                Imgproc.cvtColor(it, it, Imgproc.COLOR_BGR2Lab)
            }.useMat { lab ->
                channels.forEachIndexed { i, channel -> Core.extractChannel(lab, channel, i) }
            }
            Mat.ones(k, k, CvType.CV_8UC1).useMat { kernel ->
                Imgproc.morphologyEx(channels[0], channels[0], Imgproc.MORPH_CLOSE, kernel, Point(-1.0, -1.0), 2)
            }
            for (i in 1..2) Imgproc.medianBlur(channels[i], channels[i], k)
            val gradients = Gradients(channels, width, height)
            val threshold = maxOf(12.0, Percentiles.ofFloats(gradients.magnitude, 85.0))
            val stats = PageScore(channels, gradients, threshold, if (live) LIVE_STATS_MAX else 500.0)
            // Camera frames are luma only (a/b flat) and must be fast: no contour pass, lines from closed and plain
            // luma only, fewer candidates and smaller page statistics (lab: detect2 live profile).
            val found = if (live) {
                newMat { Imgproc.cvtColor(small, it, Imgproc.COLOR_BGR2GRAY) }.useMat { gray ->
                    LineCandidates.find(listOf(channels[0], gray), gradients, threshold, keep = LIVE_TOP_LINE)
                }
            } else {
                val clahe = Imgproc.createCLAHE(3.0, Size(8.0, 8.0))
                try {
                    newMat { Imgproc.cvtColor(small, it, Imgproc.COLOR_BGR2GRAY) }.useMat { gray ->
                        newMat { clahe.apply(channels[0], it) }.useMat { enhanced ->
                            LineCandidates.find(channels + listOf(gray, enhanced), gradients, threshold)
                        }
                    }
                } finally {
                    clahe.collectGarbage()
                    clahe.clear()
                }
            }
            val contours = if (live) emptyList() else candidates(small).map(Geometry::orderQuad)

            val scored = (contours + found.quads).mapNotNull { stats.score(it, aspectPrior) }
                .sortedByDescending { it.total }
            val winner = scored.firstOrNull() ?: return Detection(null, 0f)
            if (winner.total < MIN_SCORE) return Detection(null, winner.total.toFloat())
            val enclosing = stats.preferEnclosing(winner, scored)
            val best = if (aspectPrior == null) stats.growToSpread(enclosing, scored, found.merged) else enclosing
            val half = splitSpread(best.quad.vectors(), found.merged, width, height)?.orderedQuad()
            val quad = if (half == null) best.quad else {
                val match = scored.maxBy { quadIou(it.quad, half) }
                if (quadIou(match.quad, half) >= 0.85) match.quad else half
            }
            return Detection(Geometry.orderQuad(quad), minOf(1.0, winner.total).toFloat())
        } finally {
            channels.forEach(Mat::release)
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

    private fun collectContours(
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

    private fun magnitude(gray: Mat): Mat =
        Mat().useMat { gx ->
            Mat().useMat { gy -> newMat { lumaGradient(gray, it, gx, gy) } }
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

private fun FloatArray.divideByScale(scale: Double): FloatArray = FloatArray(size) { this[it] / scale.toFloat() }

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
