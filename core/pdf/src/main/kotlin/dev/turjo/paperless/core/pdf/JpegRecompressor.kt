package dev.turjo.paperless.core.pdf

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.graphics.scale
import java.io.File
import java.io.IOException
import kotlin.math.roundToInt

object JpegRecompressor {
    fun recompress(src: File, maxLongSide: Int?, quality: Int, dst: File) {
        require(quality in 0..100)
        encodeImage(src, maxLongSide, Bitmap.CompressFormat.JPEG, quality, dst)
    }
}

internal fun encodeImage(
    src: File,
    maxLongSide: Int?,
    format: Bitmap.CompressFormat,
    quality: Int,
    dst: File,
) {
    require(maxLongSide == null || maxLongSide > 0)
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(src.path, options)
    val width = options.outWidth
    val height = options.outHeight
    if (width <= 0 || height <= 0) throw IOException("Cannot read image dimensions: ${src.name}")

    val longSide = maxOf(width, height)
    val targetLongSide = minOf(longSide, maxLongSide ?: longSide)
    val scale = targetLongSide.toDouble() / longSide
    val targetWidth = (width * scale).roundToInt().coerceAtLeast(1)
    val targetHeight = (height * scale).roundToInt().coerceAtLeast(1)
    var sampleSize = 1
    while (longSide / sampleSize / 2 >= targetLongSide) sampleSize *= 2

    options.inJustDecodeBounds = false
    options.inSampleSize = sampleSize
    val decoded = BitmapFactory.decodeFile(src.path, options)
        ?: throw IOException("Cannot decode image: ${src.name}")
    try {
        val scaled = decoded.scale(targetWidth, targetHeight)
        try {
            dst.outputStream().use {
                if (!scaled.compress(format, quality, it)) {
                    throw IOException("Cannot encode image: ${dst.name}")
                }
            }
        } finally {
            if (scaled !== decoded) scaled.recycle()
        }
    } finally {
        decoded.recycle()
    }
}
