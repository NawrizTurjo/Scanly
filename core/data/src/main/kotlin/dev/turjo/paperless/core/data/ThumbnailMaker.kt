package dev.turjo.paperless.core.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ThumbnailMaker @Inject constructor(private val files: FileStore) {
    suspend fun make(jpeg: File, pageId: String): File = withContext(Dispatchers.IO) {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(jpeg.path, options)
        val longSide = maxOf(options.outWidth, options.outHeight)
        if (longSide <= 0) throw IOException("Could not read JPEG bounds for $jpeg")

        options.inSampleSize = 1
        while (longSide / options.inSampleSize / 2 >= MAX_SIDE) {
            options.inSampleSize *= 2
        }
        options.inJustDecodeBounds = false
        val source = BitmapFactory.decodeFile(jpeg.path, options)
            ?: throw IOException("Could not decode $jpeg")
        var thumbnail = source
        try {
            val orientation = ExifInterface(jpeg.path).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL,
            )
            val matrix = Matrix().apply {
                when (orientation) {
                    ExifInterface.ORIENTATION_FLIP_HORIZONTAL,
                    ExifInterface.ORIENTATION_TRANSPOSE,
                    ExifInterface.ORIENTATION_TRANSVERSE -> setScale(-1f, 1f)
                    ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                }
                when (orientation) {
                    ExifInterface.ORIENTATION_ROTATE_90,
                    ExifInterface.ORIENTATION_TRANSVERSE -> postRotate(90f)
                    ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
                    ExifInterface.ORIENTATION_ROTATE_270,
                    ExifInterface.ORIENTATION_TRANSPOSE -> postRotate(270f)
                }
            }
            val decodedSide = maxOf(source.width, source.height)
            if (decodedSide > MAX_SIDE) {
                val scale = MAX_SIDE.toFloat() / decodedSide
                // Round each axis independently so the transform produces a nonempty bitmap.
                matrix.preScale(
                    (source.width * scale).roundToInt().coerceAtLeast(1) / source.width.toFloat(),
                    (source.height * scale).roundToInt().coerceAtLeast(1) / source.height.toFloat(),
                )
            }
            if (!matrix.isIdentity) {
                thumbnail = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
            }
            val target = files.thumbFile(pageId)
            val temp = File.createTempFile("thumb-", ".webp", target.parentFile?.apply { mkdirs() })
            try {
                temp.outputStream().use {
                    if (!thumbnail.compress(Bitmap.CompressFormat.WEBP_LOSSY, 80, it)) {
                        throw IOException("Could not encode thumbnail for $pageId")
                    }
                }
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
                target
            } finally {
                temp.delete()
            }
        } finally {
            if (thumbnail !== source) thumbnail.recycle()
            source.recycle()
        }
    }

    private companion object {
        const val MAX_SIDE = 480
    }
}
