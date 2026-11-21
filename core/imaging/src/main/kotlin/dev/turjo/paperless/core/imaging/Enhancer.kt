package dev.turjo.paperless.core.imaging

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.photo.Photo
import kotlin.math.pow
import kotlin.math.round

object Enhancer {
    // Returned images belong to the caller, including when the filter has no effect.
    fun original(m: Mat): Mat = m.clone()

    fun auto(m: Mat): Mat {
        val means = Core.mean(m).`val`.take(3)
        val average = means.average()
        val luts = means.map { mean ->
            val gain = (average / maxOf(mean, 1.0)).pow(0.6).toFloat()
            ByteArray(256) { (it * gain).toInt().coerceIn(0, 255).toByte() }
        }
        return m.clone().useMat { balanced ->
            editRows(balanced) { row ->
                for (i in row.indices) row[i] = luts[i % 3][row[i].unsigned()]
            }
            Imgproc.cvtColor(balanced, balanced, Imgproc.COLOR_BGR2Lab)
            newMat { Core.extractChannel(balanced, it, 0) }.useMat { lightness ->
                val clahe = Imgproc.createCLAHE(2.0, Size(8.0, 8.0))
                try {
                    clahe.apply(lightness, lightness)
                    Core.insertChannel(lightness, balanced, 0)
                } finally {
                    clahe.collectGarbage()
                    clahe.clear()
                }
            }
            newMat { Imgproc.cvtColor(balanced, it, Imgproc.COLOR_Lab2BGR) }
        }
    }

    fun document(m: Mat): Mat {
        val gain = paperGain(m)
        return divideBackground(m, gain).useMat { flat ->
            stretchInPlace(flat, 0.5, 50.0, 1.25)
            Imgproc.cvtColor(flat, flat, Imgproc.COLOR_BGR2HSV)
            editRows(flat) { row ->
                for (i in 0 until row.size step 3) {
                    if (row[i + 2].unsigned() > 215) {
                        row[i + 1] = (row[i + 1].unsigned() * 0.3).toInt().toByte()
                    }
                }
            }
            Imgproc.cvtColor(flat, flat, Imgproc.COLOR_HSV2BGR)
            val amount = if (gain < 1.5) 0.5 else 0.2
            newMat { blur ->
                Imgproc.GaussianBlur(flat, blur, Size(), maxOf(0.8, m.cols() / 1600.0))
                Core.addWeighted(flat, 1 + amount, blur, -amount, 0.0, blur)
            }
        }
    }

    fun gray(m: Mat): Mat = newMat { Imgproc.cvtColor(m, it, Imgproc.COLOR_BGR2GRAY) }.useMat { gray ->
        stretchInPlace(gray, 0.5, 97.0)
        newMat { Imgproc.cvtColor(gray, it, Imgproc.COLOR_GRAY2BGR) }
    }

    fun bw(m: Mat): Mat {
        val gain = paperGain(m)
        val width = m.cols()
        return newMat { gray ->
            divideBackground(m, gain).useMat { Imgproc.cvtColor(it, gray, Imgproc.COLOR_BGR2GRAY) }
        }.useMat { mask ->
            val offset = 12 + 6 * maxOf(0.0, gain - 1.5)
            Imgproc.adaptiveThreshold(mask, mask, 255.0, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                Imgproc.THRESH_BINARY, odd(width / 40.0), offset)
            // Inverse thresholding rounds the offset differently.
            Core.bitwise_not(mask, mask)
            val minArea = maxOf(2, ((width / 1240.0).pow(2) * if (gain < 1.5) 6 else 14).toInt())
            rewriteComponents(mask, 255) { area -> area >= minArea }
            newMat { Imgproc.cvtColor(mask, it, Imgproc.COLOR_GRAY2BGR) }
        }
    }

    fun whiteboard(m: Mat): Mat = divideBackground(m).useMat { flat ->
        stretchInPlace(flat, 0.5, 60.0, 1.1)
        Imgproc.cvtColor(flat, flat, Imgproc.COLOR_BGR2HSV)
        editRows(flat) { row ->
            for (i in 1 until row.size step 3) row[i] = (row[i].unsigned() * 1.6f).toInt().coerceAtMost(255).toByte()
        }
        newMat { Imgproc.cvtColor(flat, it, Imgproc.COLOR_HSV2BGR) }
    }

    fun adjust(m: Mat, brightness: Float, contrast: Float): Mat = newMat {
        val scale = 1.0 + contrast
        Core.convertScaleAbs(m, it, scale, 128.0 * (1.0 - scale) + brightness * 100.0)
    }

    fun glareFix(m: Mat, largeShare: Double = 0.05): Pair<Mat, Boolean> {
        val limit = largeShare * m.total()
        var warning = false
        return glareMask(m).useMat { mask ->
            rewriteComponents(mask, 0) { area ->
                if (area > limit) warning = true
                area <= limit
            }
            val fixed = if (Core.countNonZero(mask) == 0) m.clone() else {
                Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0)).useMat { kernel ->
                    Imgproc.dilate(mask, mask, kernel)
                }
                newMat { Photo.inpaint(m, mask, it, maxOf(3, m.cols() / 300).toDouble(), Photo.INPAINT_TELEA) }
            }
            fixed to warning
        }
    }

    internal fun divideBackground(m: Mat, gain: Double = paperGain(m)): Mat {
        val out = Mat(m.rows(), m.cols(), m.type())
        try {
            if (gain < 1.5) {
                divideChannels(m, out)
            } else {
                newMat { Imgproc.bilateralFilter(m, it, maxOf(5, odd(m.cols() / 250.0)),
                    12.0 * gain, maxOf(5, odd(m.cols() / 250.0)).toDouble()) }.useMat { smooth ->
                    if (gain >= 2.2) Imgproc.medianBlur(smooth, smooth, 3)
                    divideChannels(smooth, out)
                }
            }
            return out
        } catch (failure: Throwable) {
            out.release()
            throw failure
        }
    }

    private fun divideChannels(source: Mat, out: Mat) {
        Mat().useMat { channel ->
            for (i in 0..2) {
                Core.extractChannel(source, channel, i)
                divideChannelInPlace(channel)
                Core.insertChannel(channel, out, i)
            }
        }
    }

    internal fun divideChannelInPlace(channel: Mat) {
        val ink = ByteArray(channel.cols())
        val paper = ByteArray(channel.cols())
        background(channel).useMat { bg ->
            for (y in 0 until channel.rows()) {
                channel.get(y, 0, ink)
                bg.get(y, 0, paper)
                for (x in ink.indices) {
                    val value = ink[x].unsigned().toFloat() / maxOf(paper[x].unsigned(), 1) * 255f
                    ink[x] = value.toInt().coerceAtMost(255).toByte()
                }
                channel.put(y, 0, ink)
            }
        }
    }

    internal fun background(channel: Mat): Mat = Mat().useMat { closed ->
        val width = channel.cols()
        val kernelSize = odd(width / 60.0).toDouble()
        Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(kernelSize, kernelSize)).useMat {
            Imgproc.morphologyEx(channel, closed, Imgproc.MORPH_CLOSE, it)
        }
        Mat().useMat { small ->
            if (minOf(channel.cols(), channel.rows()) < 3) {
                Imgproc.resize(closed, small, Size(maxOf(1.0, round(width * 0.25)),
                    maxOf(1.0, round(channel.rows() * 0.25))), 0.0, 0.0, Imgproc.INTER_AREA)
            } else {
                Imgproc.resize(closed, small, Size(), 0.25, 0.25, Imgproc.INTER_AREA)
            }
            Imgproc.medianBlur(small, small, odd(width / 4.0 / 18.0))
            newMat { Imgproc.resize(small, it, channel.size(), 0.0, 0.0, Imgproc.INTER_LINEAR) }
        }
    }

    private fun paperGain(m: Mat): Double {
        // HSV value is exactly max(B, G, R) for BGR8.
        val bins = IntArray(256)
        val row = ByteArray(m.cols() * 3)
        for (y in 0 until m.rows()) {
            m.get(y, 0, row)
            for (i in 0 until row.size step 3) bins[maxOf(row[i].unsigned(), row[i + 1].unsigned(), row[i + 2].unsigned())]++
        }
        return 255.0 / maxOf(1.0, Percentiles.ofHistogram(bins, 60.0))
    }

    private fun stretchInPlace(m: Mat, low: Double, high: Double, gamma: Double = 1.0) {
        val bins = if (m.channels() == 1) histogram(m) else {
            newMat { Imgproc.cvtColor(m, it, Imgproc.COLOR_BGR2GRAY) }.useMat(::histogram)
        }
        val lo = Percentiles.ofHistogram(bins, low)
        val hi = Percentiles.ofHistogram(bins, high)
        if (hi - lo < 10) return
        val values = ByteArray(256) {
            val scaled = ((it - lo.toFloat()) / (hi - lo).toFloat()).coerceIn(0f, 1f)
            (scaled.pow(gamma.toFloat()) * 255f).toInt().toByte()
        }
        Mat(1, 256, CvType.CV_8UC1).useMat { lut ->
            lut.put(0, 0, values)
            Core.LUT(m, lut, m)
        }
    }

    private fun glareMask(m: Mat): Mat =
        newMat { Imgproc.cvtColor(m, it, Imgproc.COLOR_BGR2HSV) }.useMat { hsv ->
            val paper = newMat { Core.extractChannel(hsv, it, 2) }.useMat {
                Percentiles.ofHistogram(histogram(it), 60.0)
            }
            val threshold = maxOf(245, (paper + 0.6 * (255 - paper)).toInt())
            newMat { mask ->
                Core.inRange(hsv, org.opencv.core.Scalar(0.0, 0.0, threshold.toDouble()),
                    org.opencv.core.Scalar(255.0, 39.0, 255.0), mask)
                Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0)).useMat {
                    Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_OPEN, it)
                }
            }
        }

    private fun rewriteComponents(mask: Mat, background: Int, keep: (Int) -> Boolean) {
        Mat().useMat { labels ->
            val values = Mat().useMat { stats ->
                Mat().useMat { centroids ->
                    val count = Imgproc.connectedComponentsWithStats(mask, labels, stats, centroids, 8)
                    ByteArray(count) { i ->
                        if (i > 0 && keep(stats.get(i, Imgproc.CC_STAT_AREA)[0].toInt()))
                            (255 - background).toByte() else background.toByte()
                    }
                }
            }
            val indices = IntArray(mask.cols())
            val row = ByteArray(mask.cols())
            for (y in 0 until mask.rows()) {
                labels.get(y, 0, indices)
                for (x in row.indices) row[x] = values[indices[x]]
                mask.put(y, 0, row)
            }
        }
    }

    private fun odd(value: Double): Int = maxOf(3, round(value).toInt()) or 1

    private inline fun editRows(m: Mat, edit: (ByteArray) -> Unit) {
        val row = ByteArray(m.cols() * m.channels())
        for (y in 0 until m.rows()) {
            m.get(y, 0, row)
            edit(row)
            m.put(y, 0, row)
        }
    }
}

internal fun Byte.unsigned(): Int = toInt() and 255

internal fun histogram(m: Mat): IntArray {
    val bins = IntArray(256)
    val row = ByteArray(m.cols())
    for (y in 0 until m.rows()) {
        m.get(y, 0, row)
        for (sample in row) bins[sample.unsigned()]++
    }
    return bins
}

