package dev.turjo.paperless.core.imaging

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.round

enum class InkColour(val hue: Int) { BLUE(110), RED(0), GREEN(60) }

/** Blocking operations on warped 8-bit pages. Colour input and output use BGR. */
object PageTools {
    fun estimateSkew(page: Mat, maxAngle: Double = 5.0, workWidth: Int = 900): Pair<Double, Double> {
        require(maxAngle.isFinite() && maxAngle >= 0 && workWidth > 0)
        val size = Size(workWidth.toDouble(), maxOf(1.0, round(page.rows().toDouble() * workWidth / page.cols())))
        return gray(page).useMat { ink ->
            Imgproc.resize(ink, ink, size, 0.0, 0.0, Imgproc.INTER_AREA)
            Enhancer.divideChannelInPlace(ink)
            Imgproc.threshold(ink, ink, 0.0, 1.0, Imgproc.THRESH_BINARY_INV or Imgproc.THRESH_OTSU)
            if (Core.countNonZero(ink) / ink.total().toDouble() < 0.002) return 0.0 to 0.0

            Mat().useMat { rotated ->
                Mat().useMat { profile ->
                    val rows = DoubleArray(ink.rows())
                    fun score(angle: Double): Double {
                        rotation(ink, angle).useMat {
                            Imgproc.warpAffine(ink, rotated, it, size, Imgproc.INTER_NEAREST)
                        }
                        Core.reduce(rotated, profile, 1, Core.REDUCE_SUM, CvType.CV_64F)
                        profile.get(0, 0, rows)
                        val mean = rows.average()
                        return rows.sumOf { (it - mean) * (it - mean) } / rows.size
                    }

                    val coarse = DoubleArray(((2 * maxAngle + 1e-6) / 0.5).toInt() + 1) {
                        score(-maxAngle + it * 0.5)
                    }
                    val bestIndex = coarse.indices.maxBy { coarse[it] }
                    val fineStart = -maxAngle + bestIndex * 0.5 - 0.5
                    val fineIndex = (0..40).maxBy { score(fineStart + it * 0.025) }
                    val peak = coarse[bestIndex]
                    fineStart + fineIndex * 0.025 to (1.0 - median(coarse) / maxOf(peak, 1e-9))
                }
            }
        }
    }

    fun deskew(page: Mat, minConf: Double = 0.25, minAngle: Double = 0.3): Pair<Mat, Double> {
        val (angle, confidence) = estimateSkew(page)
        if (confidence < minConf || abs(angle) < minAngle) return page.clone() to 0.0
        return rotation(page, angle).useMat { transform ->
            newMat {
                Imgproc.warpAffine(page, it, transform, page.size(), Imgproc.INTER_CUBIC, Core.BORDER_REPLICATE)
            } to angle
        }
    }

    fun inkFraction(page: Mat): Double = Enhancer.document(page).useMat { document ->
        Imgproc.cvtColor(document, document, Imgproc.COLOR_BGR2GRAY)
        val mx = (document.cols() * 0.04).toInt()
        val my = (document.rows() * 0.04).toInt()
        document.submat(Rect(mx, my, document.cols() - 2 * mx, document.rows() - 2 * my)).useMat { center ->
            newMat { Imgproc.threshold(center, it, 139.0, 1.0, Imgproc.THRESH_BINARY_INV) }.useMat { ink ->
                Mat.ones(2, 2, CvType.CV_8UC1).useMat { kernel ->
                    Imgproc.morphologyEx(ink, ink, Imgproc.MORPH_OPEN, kernel)
                }
                Core.countNonZero(ink) / ink.total().toDouble()
            }
        }
    }

    fun isBlank(page: Mat, threshold: Double = 0.001): Boolean = inkFraction(page) < threshold

    fun removeInk(page: Mat, colour: InkColour, hueTol: Int = 15, minChroma: Int = 45): Mat =
        newMat { Imgproc.cvtColor(page, it, Imgproc.COLOR_BGR2HSV) }.useMat { hsv ->
            Mat(page.rows(), page.cols(), CvType.CV_8UC1).useMat { mask ->
                val bgr = ByteArray(page.cols() * 3)
                val hues = ByteArray(bgr.size)
                val selected = ByteArray(page.cols())
                for (y in 0 until page.rows()) {
                    page.get(y, 0, bgr)
                    hsv.get(y, 0, hues)
                    for (x in selected.indices) {
                        val i = 3 * x
                        val b = bgr[i].unsigned()
                        val g = bgr[i + 1].unsigned()
                        val r = bgr[i + 2].unsigned()
                        val distance = abs(hues[i].unsigned() - colour.hue)
                        val chroma = maxOf(b, g, r) - minOf(b, g, r)
                        selected[x] = if (minOf(distance, 180 - distance) <= hueTol && chroma >= minChroma) -1 else 0
                    }
                    mask.put(y, 0, selected)
                }

                Mat.ones(5, 5, CvType.CV_8UC1).useMat { Imgproc.dilate(mask, mask, it) }
                newMat { output ->
                    page.copyTo(output)
                    if (Core.countNonZero(mask) > 0) {
                        Mat().useMat { channel ->
                            for (i in 0..2) {
                                Core.extractChannel(page, channel, i)
                                Enhancer.background(channel).useMat { it.copyTo(channel, mask) }
                                Core.insertChannel(channel, output, i)
                            }
                        }
                    }
                }
            }
        }

    fun findGutter(spread: Mat, lo: Double = 0.3, hi: Double = 0.7): Pair<Int, Double> {
        require(lo >= 0.0 && hi <= 1.0 && lo < hi)
        val width = spread.cols()
        val start = (width * lo).toInt()
        val end = (width * hi).toInt().coerceAtLeast(start + 1)
        val k = maxOf(5, (width / 60) or 1)
        return gray(spread).useMat { paper ->
            Mat.ones(k, k, CvType.CV_8UC1).useMat { Imgproc.morphologyEx(paper, paper, Imgproc.MORPH_CLOSE, it) }
            Mat().useMat { profile ->
                Core.reduce(paper, profile, 0, Core.REDUCE_AVG, CvType.CV_32F)
                Imgproc.GaussianBlur(profile, profile, Size(), maxOf(2.0, width / 200.0))
                val band = FloatArray(end - start)
                profile.get(0, start, band)
                val darkest = band.indices.minBy { band[it] }
                val depth = median(DoubleArray(band.size) { band[it].toDouble() }) - band[darkest]
                start + darkest to minOf(1.0, depth / 25.0)
            }
        }
    }

    private fun gray(page: Mat): Mat = if (page.channels() == 1) page.clone()
        else newMat { Imgproc.cvtColor(page, it, Imgproc.COLOR_BGR2GRAY) }

    private fun rotation(page: Mat, angle: Double): Mat =
        org.opencv.geometry.Geometry.getRotationMatrix2D(Point(page.cols() / 2.0, page.rows() / 2.0), angle, 1.0)

    private fun median(values: DoubleArray): Double {
        values.sort()
        return (values[(values.size - 1) / 2] + values[values.size / 2]) / 2
    }
}
