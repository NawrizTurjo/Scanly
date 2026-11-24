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
    val deskew: Boolean = false,
    val inkRemoval: String? = null,
)

data class ProcessResult(val width: Int, val height: Int, val glareWarning: Boolean)

class PageProcessor {
    /** Blocking. Call on a worker thread. Quad coordinates are in the EXIF-oriented image. */
    fun process(originalJpeg: File, params: ProcessParams, out: File, jpegQuality: Int = 92): ProcessResult {
        require(jpegQuality in 0..100)
        require(params.rotation % 90 == 0) { "Rotation must be a multiple of 90 degrees" }
        OpenCv.ensureLoaded()
        val timer = StageTimer()
        val orientation = exifOrientation(originalJpeg)
        var page = decodeJpeg(originalJpeg, orientation)
        timer.mark("decode ${page.cols()}x${page.rows()}")
        var warning = false

        fun replace(next: Mat, stage: String) {
            page.release()
            page = next
            timer.mark(stage)
        }

        try {
            if (params.quad != null) replace(Geometry.warp(page, params.quad, snap = params.snapToPaper), "warp")
            params.inkRemoval?.let { replace(PageTools.removeInk(page, InkColour.valueOf(it)), "ink") }
            if (params.deskew) replace(PageTools.deskew(page).first, "deskew")
            if (params.glareFix) {
                val (fixed, glareWarning) = Enhancer.glareFix(page)
                replace(fixed, "glare")
                warning = glareWarning
            }
            // Document, BW, and Whiteboard already divide by the background estimate.
            if (params.shadowFix && params.filter !in setOf("DOCUMENT", "BW", "WHITEBOARD")) {
                replace(Enhancer.divideBackground(page), "shadow")
            }
            when (params.filter) {
                "ORIGINAL" -> Unit
                "AUTO" -> replace(Enhancer.auto(page), "filter AUTO")
                "DOCUMENT" -> replace(Enhancer.document(page), "filter DOCUMENT")
                "BW" -> replace(Enhancer.bw(page), "filter BW")
                "GRAY" -> replace(Enhancer.gray(page), "filter GRAY")
                "WHITEBOARD" -> replace(Enhancer.whiteboard(page), "filter WHITEBOARD")
                else -> throw IllegalArgumentException("Unknown imaging filter: ${params.filter}")
            }
            if (params.brightness != 0f || params.contrast != 0f) {
                replace(Enhancer.adjust(page, params.brightness, params.contrast), "adjust")
            }
            when (Math.floorMod(params.rotation, 360)) {
                90 -> replace(newMat { Core.rotate(page, it, Core.ROTATE_90_CLOCKWISE) }, "rotate")
                180 -> Core.rotate(page, page, Core.ROTATE_180)
                270 -> replace(newMat { Core.rotate(page, it, Core.ROTATE_90_COUNTERCLOCKWISE) }, "rotate")
            }
            writeJpeg(page, out, jpegQuality)
            timer.mark("encode")
            timer.log("process")
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
fun detectOnFile(jpeg: File, maxSide: Int = 2000, aspectPrior: Float? = null): Detection {
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
            val result = QuadDetector().detect(working, aspectPrior = aspectPrior)
            val quad = result.quad ?: return@withResized result
            val transposed = orientation in ExifInterface.ORIENTATION_TRANSPOSE..ExifInterface.ORIENTATION_ROTATE_270
            val width = if (transposed) bounds.outHeight else bounds.outWidth
            val height = if (transposed) bounds.outWidth else bounds.outHeight
            val sx = width.toFloat() / working.cols()
            val sy = height.toFloat() / working.rows()
            // Scaling a border corner can land a fraction past the image; the crop editor needs points inside it.
            result.copy(quad = FloatArray(8) {
                if (it % 2 == 0) (quad[it] * sx).coerceIn(0f, width.toFloat())
                else (quad[it] * sy).coerceIn(0f, height.toFloat())
            })
        }
    }
}

/**
 * Blocking. Snaps a quad the live camera overlay already found ([hint]: normalized upright TL, TR, BR, BL) to the
 * page edges at detection resolution, skipping the full candidate search (about 2 s for a 12 MP photo). Returns
 * pixel coordinates like [detectOnFile], or null when the edges don't fit (callers then run the full detection).
 */
fun refineOnFile(jpeg: File, hint: FloatArray, maxSide: Int = 2000): FloatArray? {
    require(hint.size == 8)
    OpenCv.ensureLoaded()
    val timer = StageTimer()
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(jpeg.path, bounds)
    require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Unable to read JPEG dimensions: $jpeg" }
    val orientation = exifOrientation(jpeg)
    val longestSide = maxOf(bounds.outWidth, bounds.outHeight)
    val mode = when {
        longestSide.toLong() >= 4L * maxSide -> Imgcodecs.IMREAD_REDUCED_COLOR_4
        longestSide.toLong() >= 2L * maxSide -> Imgcodecs.IMREAD_REDUCED_COLOR_2
        else -> Imgcodecs.IMREAD_COLOR
    }
    return decodeJpeg(jpeg, orientation, mode).useMat { decoded ->
        withResized(decoded, imageScale(decoded, maxSide)) { working ->
            val w = working.cols().toFloat()
            val h = working.rows().toFloat()
            val start = FloatArray(8) { if (it % 2 == 0) hint[it] * w else hint[it] * h }
            val refined = QuadDetector().refineHint(working, start) ?: return@withResized null
            val transposed = orientation in ExifInterface.ORIENTATION_TRANSPOSE..ExifInterface.ORIENTATION_ROTATE_270
            val width = if (transposed) bounds.outHeight else bounds.outWidth
            val height = if (transposed) bounds.outWidth else bounds.outHeight
            val sx = width / w
            val sy = height / h
            FloatArray(8) {
                if (it % 2 == 0) (refined[it] * sx).coerceIn(0f, width.toFloat())
                else (refined[it] * sy).coerceIn(0f, height.toFloat())
            }
        }
    }.also { timer.mark(if (it == null) "no fit" else "fit"); timer.log("refine hint") }
}

internal fun exifOrientation(jpeg: File): Int = ExifInterface(jpeg.path)
    .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)

internal fun decodeJpeg(file: File, orientation: Int, mode: Int = Imgcodecs.IMREAD_COLOR): Mat = newMat { image ->
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



/** Debug-level stage timings (tag ScanTiming) so slow steps show up in logcat on a real phone. */
internal class StageTimer {
    private val start = System.nanoTime()
    private var last = start
    private val parts = StringBuilder()

    fun mark(stage: String) {
        val now = System.nanoTime()
        parts.append(stage).append(' ').append((now - last) / 1_000_000).append(" ms, ")
        last = now
    }

    fun log(what: String) {
        android.util.Log.d("ScanTiming", "$what ${(System.nanoTime() - start) / 1_000_000} ms: $parts")
    }
}
