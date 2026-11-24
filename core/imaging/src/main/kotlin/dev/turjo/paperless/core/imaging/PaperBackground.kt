package dev.turjo.paperless.core.imaging

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.photo.Photo
import org.opencv.geometry.Geometry as CvGeometry
import kotlin.math.hypot
import kotlin.math.round

/**
 * Paper estimate for the Document filter (lab `background_v2`). The old estimate took any large block for paper, so
 * shaded table headers went white and dark or coloured labels went black. This one works on a 512 px copy, cuts out
 * printed blocks (coloured fills, very dark blocks, sharp-edged grey fills away from the border) and fills the paper
 * level under them from the paper around them. Soft shadows that run off the page are still flattened.
 */
internal object PaperBackground {
    const val WORK_WIDTH = 512

    /** Full-size paper estimate (8UC3) and the small 0/1 printed-block mask (8UC1, null when there are none). */
    class Estimate(val paper: Mat, val printed: Mat?) : AutoCloseable {
        override fun close() {
            paper.release()
            printed?.release()
        }
    }

    fun estimate(image: Mat): Estimate {
        val scale = if (image.cols() > WORK_WIDTH) WORK_WIDTH.toDouble() / image.cols() else 1.0
        val small = newMat { Imgproc.resize(image, it, Size(), scale, scale, Imgproc.INTER_AREA) }
        return small.useMat {
            val width = small.cols()
            val k = odd(width / 60.0).toDouble()
            newMat { closed ->
                Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(k, k)).useMat { kernel ->
                    Imgproc.morphologyEx(small, closed, Imgproc.MORPH_CLOSE, kernel)
                }
            }.useMat { closed ->
                val printed = printedMask(closed)
                val any = Core.countNonZero(printed) > 0
                try {
                    val paper = newMat { bg ->
                        Imgproc.medianBlur(closed, bg, odd(width / 18.0))
                        if (any) fillFromPaper(bg, printed, width)
                    }.useMat { bg ->
                        newMat { Imgproc.resize(bg, it, image.size(), 0.0, 0.0, Imgproc.INTER_LINEAR) }
                    }
                    if (!any) printed.release()
                    Estimate(paper, printed.takeIf { any })
                } catch (failure: Throwable) {
                    printed.release()
                    throw failure
                }
            }
        }
    }

    /**
     * Replaces [bg] under printed blocks with a weighted average of the paper around them (normalised convolution,
     * lab `_fill_from_paper`). Smooth across lighting gradients; inpainting streaked inside large blocks.
     */
    private fun fillFromPaper(bg: Mat, printed: Mat, width: Int) {
        val sigma = width / 10.0
        Mat().useMat { valid ->
            printed.convertTo(valid, CvType.CV_32F, -1.0, 1.0)
            newMat { Imgproc.GaussianBlur(valid, it, Size(), sigma) }.useMat { den ->
                Core.max(den, Scalar(1e-4), den)
                Mat().useMat { bgf ->
                    bg.convertTo(bgf, CvType.CV_32F)
                    val channels = mutableListOf<Mat>()
                    Core.split(bgf, channels)
                    try {
                        for (channel in channels) {
                            newMat { num ->
                                Core.multiply(channel, valid, num)
                                Imgproc.GaussianBlur(num, num, Size(), sigma)
                                Core.divide(num, den, num)
                            }.useMat { fill -> fill.copyTo(channel, printed) }
                        }
                        Core.merge(channels, bgf)
                    } finally {
                        channels.forEach(Mat::release)
                    }
                    Imgproc.GaussianBlur(bgf, bgf, Size(), width / 200.0)
                    bgf.convertTo(bg, CvType.CV_8U)
                }
            }
        }
    }

    /** 0/1 mask (8UC1, size of [closed]) of printed blocks on the closed, text-free small page. */
    internal fun printedMask(closed: Mat): Mat {
        val w = closed.cols()
        val h = closed.rows()
        val n = w * h
        val lightness = FloatArray(n)
        val chroma = FloatArray(n)
        newMat { Imgproc.cvtColor(closed, it, Imgproc.COLOR_BGR2Lab) }.useMat { lab ->
            val row = ByteArray(w * 3)
            for (y in 0 until h) {
                lab.get(y, 0, row)
                for (x in 0 until w) {
                    val i = y * w + x
                    lightness[i] = row[x * 3].unsigned().toFloat()
                    chroma[i] = hypot(row[x * 3 + 1].unsigned() - 128f, row[x * 3 + 2].unsigned() - 128f)
                }
            }
        }
        val lMat = Mat(h, w, CvType.CV_32FC1).apply { put(0, 0, lightness) }
        val env = FloatArray(n)
        val grad = FloatArray(n)
        lMat.useMat { l ->
            val k = odd(w / 2.5).toDouble()
            newMat { dilated ->
                Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(k, k)).useMat { Imgproc.dilate(l, dilated, it) }
                Imgproc.GaussianBlur(dilated, dilated, Size(), w / 16.0)
            }.useMat { it.get(0, 0, env) }
            newMat { gx -> Imgproc.Sobel(l, gx, CvType.CV_32F, 1, 0, 3) }.useMat { gx ->
                newMat { gy -> Imgproc.Sobel(l, gy, CvType.CV_32F, 0, 1, 3) }.useMat { gy ->
                    newMat { Core.magnitude(gx, gy, it) }.useMat { it.get(0, 0, grad) }
                }
            }
        }
        for (i in grad.indices) grad[i] /= 8f

        val minArea = n * 0.0005
        val out = Mat.zeros(h, w, CvType.CV_8UC1)
        try {
            maskOf(w, h) { chroma[it] > 22f }.useMat { coloured ->
                openInPlace(coloured)
                fillComponents(coloured, out, minArea)
            }
            maskOf(w, h) { lightness[it] < env[it] * 0.5f }.useMat { deep ->
                openInPlace(deep)
                forEachComponent(deep) { _, rect, area, component ->
                    val inside = rect.x > 0 && rect.y > 0 && rect.x + rect.width < w && rect.y + rect.height < h
                    if (area >= minArea && inside) fillComponents(component, out, 0.0, rect)
                }
            }
            maskOf(w, h) { lightness[it] < env[it] * 0.94f && chroma[it] <= 22f }.useMat { dark ->
                openInPlace(dark)
                forEachComponent(dark) { _, rect, area, component ->
                    if (area < minArea) return@forEachComponent
                    var contrast = 0.0
                    var count = 0
                    val crop = ByteArray(rect.width * rect.height)
                    component.get(0, 0, crop)
                    for (yy in 0 until rect.height) for (xx in 0 until rect.width) {
                        if (crop[yy * rect.width + xx].toInt() == 0) continue
                        val i = (rect.y + yy) * w + rect.x + xx
                        contrast += env[i] - lightness[i]
                        count++
                    }
                    contrast /= maxOf(count, 1)
                    val band = newMat { b ->
                        Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0)).useMat {
                            Imgproc.morphologyEx(component, b, Imgproc.MORPH_GRADIENT, it)
                        }
                    }.useMat { b -> ByteArray(rect.width * rect.height).also { b.get(0, 0, it) } }
                    var edge = 0.0
                    var edgeCount = 0
                    for (yy in 0 until rect.height) for (xx in 0 until rect.width) {
                        if (band[yy * rect.width + xx].toInt() == 0) continue
                        edge += grad[(rect.y + yy) * w + rect.x + xx]
                        edgeCount++
                    }
                    edge = if (edgeCount > 0) edge / edgeCount else 0.0
                    val sharp = contrast > 1 && edge / contrast > 0.22
                    val core = rect.componentRect
                    val onBorder = core.x == 0 || core.y == 0 || core.x + core.width == w || core.y + core.height == h
                    val fill = area / (core.width.toDouble() * core.height)
                    if (sharp && !onBorder && (fill > 0.6 || area < n * 0.08)) fillComponents(component, out, 0.0, rect)
                }
            }
            Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0)).useMat { Imgproc.dilate(out, out, it) }
            return out
        } catch (failure: Throwable) {
            out.release()
            throw failure
        }
    }

    /** A crop rectangle padded by 2 px, remembering the component's own bounding box. */
    private class PaddedRect(x: Int, y: Int, width: Int, height: Int, val componentRect: Rect) : Rect(x, y, width, height)

    private inline fun maskOf(w: Int, h: Int, test: (Int) -> Boolean): Mat {
        val bytes = ByteArray(w * h) { if (test(it)) 1 else 0 }
        return Mat(h, w, CvType.CV_8UC1).apply { put(0, 0, bytes) }
    }

    private fun openInPlace(mask: Mat) {
        Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0)).useMat {
            Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_OPEN, it)
        }
    }

    /** Calls [action] with each component's crop (0/1 mask, padded by 2 px), its rect and its pixel area. */
    private inline fun forEachComponent(mask: Mat, action: (Int, PaddedRect, Int, Mat) -> Unit) {
        val w = mask.cols()
        val h = mask.rows()
        Mat().useMat { labels ->
            Mat().useMat { stats ->
                Mat().useMat { centroids ->
                    val count = Imgproc.connectedComponentsWithStats(mask, labels, stats, centroids, 8)
                    for (i in 1 until count) {
                        val s = IntArray(5)
                        stats.get(i, 0, s)
                        val x0 = maxOf(0, s[0] - 2)
                        val y0 = maxOf(0, s[1] - 2)
                        val x1 = minOf(w, s[0] + s[2] + 2)
                        val y1 = minOf(h, s[1] + s[3] + 2)
                        val rect = PaddedRect(x0, y0, x1 - x0, y1 - y0, Rect(s[0], s[1], s[2], s[3]))
                        newMat { component ->
                            labels.submat(rect).useMat { Core.compare(it, Scalar(i.toDouble()), component, Core.CMP_EQ) }
                            Core.min(component, Scalar(1.0), component)
                        }.useMat { component -> action(i, rect, s[4], component) }
                    }
                }
            }
        }
    }

    /** Fills the outer contours of [mask] (holes such as letters included) with 1 into [out] at [offset]. */
    private fun fillComponents(mask: Mat, out: Mat, minArea: Double, offset: Rect = Rect(0, 0, mask.cols(), mask.rows())) {
        val contours = mutableListOf<MatOfPoint>()
        Mat().useMat { hierarchy ->
            Imgproc.findContours(mask, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        }
        try {
            val big = contours.filter { CvGeometry.contourArea(it) >= minArea }
            if (big.isNotEmpty()) {
                out.submat(offset).useMat { target ->
                    Imgproc.drawContours(target, big, -1, Scalar(1.0), -1)
                }
            }
        } finally {
            contours.forEach(Mat::release)
        }
    }

    private fun odd(value: Double): Int = maxOf(3, round(value).toInt()) or 1
}
