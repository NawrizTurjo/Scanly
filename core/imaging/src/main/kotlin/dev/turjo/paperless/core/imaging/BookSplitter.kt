package dev.turjo.paperless.core.imaging

import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.File
import kotlin.math.round

object BookSplitter {
    /** Blocking. Both output quads use the original, EXIF-upright pixel coordinates. */
    fun split(original: File, spread: FloatArray): Pair<FloatArray, FloatArray>? {
        OpenCv.ensureLoaded()
        return decodeJpeg(original, exifOrientation(original)).useMat { split(it, spread) }
    }

    internal fun split(image: Mat, spread: FloatArray): Pair<FloatArray, FloatArray>? {
        val q = Geometry.orderQuad(spread)
        val (width, height) = Geometry.sideLengths(q)
        val scale = 1200.0 / maxOf(width, height)
        val size = Size(round(width * scale), round(height * scale))
        if (size.width < 2 || size.height < 2) return null
        return Geometry.warpTransform(q, size).useMat { transform ->
            newMat { Imgproc.warpPerspective(image, it, transform, size, Imgproc.INTER_LINEAR, Core.BORDER_REPLICATE) }
                .useMat { warped ->
                    val (x, confidence) = PageTools.findGutter(warped)
                    if (confidence < 0.3) return null
                    val points = MatOfPoint2f(Point(x.toDouble(), 0.0), Point(x.toDouble(), size.height - 1))
                        .useMat { gutter ->
                            transform.inv().useMat { inverse ->
                                MatOfPoint2f().useMat {
                                    Core.perspectiveTransform(gutter, it, inverse)
                                    it.toArray()
                                }
                            }
                        }
                    val tx = points[0].x.toFloat()
                    val ty = points[0].y.toFloat()
                    val bx = points[1].x.toFloat()
                    val by = points[1].y.toFloat()
                    floatArrayOf(q[0], q[1], tx, ty, bx, by, q[6], q[7]) to
                        floatArrayOf(tx, ty, q[2], q[3], q[4], q[5], bx, by)
                }
        }
    }
}
