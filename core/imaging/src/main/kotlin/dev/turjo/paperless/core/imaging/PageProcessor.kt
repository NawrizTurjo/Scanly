package dev.turjo.paperless.core.imaging

import android.graphics.BitmapFactory
import android.media.ExifInterface
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfInt
import org.opencv.imgcodecs.Imgcodecs
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

data class ProcessParams(
    val quad: FloatArray?,
    val rotation: Int,
    val filter: String,
    val brightness: Float,
    val contrast: Float,
    val shadowFix: Boolean,
    val glareFix: Boolean,
    val snapToPaper: Boolean = true,
)

data class ProcessResult(val width: Int, val height: Int, val glareWarning: Boolean)

class PageProcessor {
    /** Blocking. Call on a worker thread. Quad coordinates are in the EXIF-oriented image. */
    fun process(originalJpeg: File, params: ProcessParams, out: File, jpegQuality: Int = 92): ProcessResult {
        require(jpegQuality in 0..100)
        require(params.rotation % 90 == 0) { "Rotation must be a multiple of 90 degrees" }
        OpenCv.ensureLoaded()
        val orientation = exifOrientation(originalJpeg)
        var page = decodeJpeg(originalJpeg, orientation)
        var warning = false

        fun replace(next: Mat) {
            page.release()
            page = next
        }

        try {
            if (params.quad != null) replace(Geometry.warp(page, params.quad, snap = params.snapToPaper))
            if (params.glareFix) {
                val (fixed, glareWarning) = Enhancer.glareFix(page)
                replace(fixed)
                warning = glareWarning
            }
            // Document, BW, and Whiteboard already divide by the background estimate.
            if (params.shadowFix && params.filter !in setOf("DOCUMENT", "BW", "WHITEBOARD")) {
                replace(Enhancer.divideBackground(page))
            }
            when (params.filter) {
                "ORIGINAL" -> Unit
                "AUTO" -> replace(Enhancer.auto(page))
                "DOCUMENT" -> replace(Enhancer.document(page))
                "BW" -> replace(Enhancer.bw(page))
                "GRAY" -> replace(Enhancer.gray(page))
                "WHITEBOARD" -> replace(Enhancer.whiteboard(page))
                else -> throw IllegalArgumentException("Unknown imaging filter: ${params.filter}")
            }
            if (params.brightness != 0f || params.contrast != 0f) {
                replace(Enhancer.adjust(page, params.brightness, params.contrast))
            }
            when (Math.floorMod(params.rotation, 360)) {
                90 -> replace(newMat { Core.rotate(page, it, Core.ROTATE_90_CLOCKWISE) })
                180 -> Core.rotate(page, page, Core.ROTATE_180)
                270 -> replace(newMat { Core.rotate(page, it, Core.ROTATE_90_COUNTERCLOCKWISE) })
            }
            writeJpeg(page, out, jpegQuality)
            return ProcessResult(page.cols(), page.rows(), warning)
        } finally {
            page.release()
        }
    }

    private fun writeJpeg(page: Mat, out: File, quality: Int) {
        val destination = out.absoluteFile
        val temp = File.createTempFile("scanly-", ".jpg", destination.parentFile)
        try {
            MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, quality).useMat { options ->
                check(Imgcodecs.imwrite(temp.path, page, options)) { "Unable to encode JPEG: $destination" }
            }
            Files.move(temp.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temp.delete()
        }
    }
}

/** Blocking. Returns pixel coordinates in the full-size, EXIF-oriented JPEG. */
fun detectOnFile(jpeg: File, maxSide: Int = 2000): Detection {
    require(maxSide > 0)
    OpenCv.ensureLoaded()
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(jpeg.path, bounds)
    require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Unable to read JPEG dimensions: $jpeg" }
    val orientation = exifOrientation(jpeg)
    val longestSide = maxOf(bounds.outWidth, bounds.outHeight)
    val mode = when {
        longestSide.toLong() >= 8L * maxSide -> Imgcodecs.IMREAD_REDUCED_COLOR_8
        longestSide.toLong() >= 4L * maxSide -> Imgcodecs.IMREAD_REDUCED_COLOR_4
        longestSide.toLong() >= 2L * maxSide -> Imgcodecs.IMREAD_REDUCED_COLOR_2
        else -> Imgcodecs.IMREAD_COLOR
    }

    return decodeJpeg(jpeg, orientation, mode).useMat { decoded ->
        withResized(decoded, imageScale(decoded, maxSide)) { working ->
            val result = QuadDetector().detect(working)
            val quad = result.quad ?: return@withResized result
            val transposed = orientation in ExifInterface.ORIENTATION_TRANSPOSE..ExifInterface.ORIENTATION_ROTATE_270
            val width = if (transposed) bounds.outHeight else bounds.outWidth
            val height = if (transposed) bounds.outWidth else bounds.outHeight
            val sx = width.toFloat() / working.cols()
            val sy = height.toFloat() / working.rows()
            result.copy(quad = FloatArray(8) { quad[it] * if (it % 2 == 0) sx else sy })
        }
    }
}

private fun exifOrientation(jpeg: File): Int = ExifInterface(jpeg.path)
    .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)

private fun decodeJpeg(file: File, orientation: Int, mode: Int = Imgcodecs.IMREAD_COLOR): Mat = newMat { image ->
    Imgcodecs.imread(file.path, image, mode or Imgcodecs.IMREAD_IGNORE_ORIENTATION)
    require(!image.empty()) { "Unable to decode JPEG: $file" }
    when (orientation) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> Core.flip(image, image, 1)
        ExifInterface.ORIENTATION_ROTATE_180 -> Core.rotate(image, image, Core.ROTATE_180)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> Core.flip(image, image, 0)
        ExifInterface.ORIENTATION_TRANSPOSE -> Core.transpose(image, image)
        ExifInterface.ORIENTATION_ROTATE_90 -> Core.rotate(image, image, Core.ROTATE_90_CLOCKWISE)
        ExifInterface.ORIENTATION_TRANSVERSE -> {
            Core.transpose(image, image)
            Core.flip(image, image, -1)
        }
        ExifInterface.ORIENTATION_ROTATE_270 -> Core.rotate(image, image, Core.ROTATE_90_COUNTERCLOCKWISE)
    }
}

