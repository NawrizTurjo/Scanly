package dev.turjo.paperless.core.imaging

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.MatOfPoint
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.nio.ByteBuffer
import kotlin.math.round

/** Luma starts at the buffer position. Plane rows may be padded (rowStride >= width). */
class LumaFrame(
    val data: ByteBuffer,
    val width: Int,
    val height: Int,
    val rowStride: Int,
    val rotationDegrees: Int,
)

/** Eight coordinates in TL, TR, BR, BL order, normalized to the upright frame. */
data class LiveQuad(
    val points: FloatArray,
    val confidence: Float,
    val sharpness: Float = 0f,
    val fingerprint: FloatArray = FloatArray(0),
)

class LiveQuadDetector {
    private val detector = QuadDetector()
    private var workspace: Workspace? = null

    /** Blocking and thread-confined. Call from the analysis thread only. */
    fun detect(frame: LumaFrame): LiveQuad? {
        val width = frame.width
        val height = frame.height
        val stride = frame.rowStride
        require(width > 0 && height > 0 && stride >= width)
        val data = frame.data.slice()
        require((height - 1L) * stride + width <= data.remaining())
        val rotation = when (frame.rotationDegrees) {
            0 -> null
            90 -> Core.ROTATE_90_CLOCKWISE
            180 -> Core.ROTATE_180
            270 -> Core.ROTATE_90_COUNTERCLOCKWISE
            else -> throw IllegalArgumentException("Expected a rotation of 0, 90, 180 or 270 degrees")
        }
        val work = workspace ?: run {
            OpenCv.ensureLoaded()
            Workspace().also { workspace = it }
        }
        if (width == stride && data.isDirect) {
            return Mat(height, width, CvType.CV_8UC1, data).useMat { detectUpright(it, rotation, work) }
        }

        val size = width * height
        if (work.packed.capacity() < size) work.packed = ByteBuffer.allocateDirect(size)
        if (work.row.size != width) work.row = ByteArray(width)
        work.packed.clear()
        for (y in 0 until height) {
            data.position(y * stride)
            data.get(work.row)
            work.packed.put(work.row)
        }
        work.packed.flip()
        return Mat(height, width, CvType.CV_8UC1, work.packed).useMat { detectUpright(it, rotation, work) }
    }

    private fun detectUpright(input: Mat, rotation: Int?, work: Workspace): LiveQuad? {
        val upright = if (rotation == null) input else work.upright.also { Core.rotate(input, it, rotation) }
        val detection = detector.detectLive(upright)
        val points = detection.quad ?: return null
        val scale = imageScale(upright, LIVE_WORK_MAX)
        val small = if (scale == 1.0) upright else work.small.also {
            val size = Size(maxOf(1.0, round(upright.cols() * scale)), maxOf(1.0, round(upright.rows() * scale)))
            Imgproc.resize(upright, it, size, 0.0, 0.0, Imgproc.INTER_AREA)
        }
        work.mask.create(small.rows(), small.cols(), CvType.CV_8UC1)

        val sharpness = sharpness(small, points, work)
        val fingerprint = Geometry.warp(small, points, Size(16.0, 16.0)).useMat { patch ->
            val bytes = ByteArray(256)
            patch.get(0, 0, bytes)
            FloatArray(bytes.size) { bytes[it].unsigned() / 255f }
        }
        val scaledWidth = scale * upright.cols()
        val scaledHeight = scale * upright.rows()
        for (i in points.indices step 2) {
            points[i] = (points[i] / scaledWidth).toFloat().coerceIn(0f, 1f)
            points[i + 1] = (points[i + 1] / scaledHeight).toFloat().coerceIn(0f, 1f)
        }
        return LiveQuad(points, detection.confidence, sharpness, fingerprint)
    }

    private fun sharpness(gray: Mat, quad: FloatArray, work: Workspace): Float {
        work.mask.setTo(Scalar(0.0)).release()
        MatOfPoint(*quad.points()).useMat { polygon ->
            Imgproc.fillConvexPoly(work.mask, polygon, Scalar(255.0))
        }
        Imgproc.Laplacian(gray, work.gx, CvType.CV_32F)
        return MatOfDouble().useMat { mean ->
            MatOfDouble().useMat { deviation ->
                Core.meanStdDev(work.gx, mean, deviation, work.mask)
                val sigma = deviation.toArray()[0]
                (sigma * sigma).toFloat()
            }
        }
    }

    /** Releases native buffers. Call when no detection is running. */
    fun release() {
        workspace?.release()
        workspace = null
    }

    private class Workspace {
        val upright = Mat()
        val small = Mat()
        val mask = Mat()
        val gx = Mat()
        var packed: ByteBuffer = ByteBuffer.allocateDirect(0)
        var row = ByteArray(0)

        fun release() {
            listOf(upright, small, mask, gx).forEach(Mat::release)
        }
    }
}
