package dev.turjo.paperless.core.pdf.tools

import android.graphics.BitmapFactory
import androidx.core.graphics.scale
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import dev.turjo.paperless.core.pdf.JpegRecompressor
import dev.turjo.paperless.core.pdf.PageLayout
import dev.turjo.paperless.core.pdf.PageSizeOption
import dev.turjo.paperless.core.pdf.QualityPreset
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

object ImagesToPdf {
    suspend fun convert(
        images: List<File>,
        option: PageSizeOption,
        quality: QualityPreset,
        out: File,
    ): ToolResult = withContext(Dispatchers.IO) {
        requireTool(images.isNotEmpty(), "Pick at least one image")
        PDDocument(MemoryUsageSetting.setupTempFileOnly()).use { doc ->
            for (source in images) {
                ensureActive()
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(source.path, bounds)
                val width = bounds.outWidth
                val height = bounds.outHeight
                if (width <= 0 || height <= 0) throw ToolError.Corrupt("Cannot read ${source.name}")
                val box = PageLayout.pageBox(option, width, height)
                val position = PageLayout.placement(option, width, height)
                val page = PDPage(PDRectangle(box.width, box.height))
                val image = if (bounds.outMimeType == "image/jpeg") {
                    val jpegQuality = quality.jpegQuality
                    val temp = jpegQuality?.let { File.createTempFile("pdf-image-", ".jpg") }
                    try {
                        if (temp != null) JpegRecompressor.recompress(source, quality.maxLongSide, jpegQuality, temp)
                        (temp ?: source).inputStream().use { JPEGFactory.createFromStream(doc, it) }
                    } finally {
                        temp?.delete()
                    }
                } else {
                    val longSide = maxOf(width, height)
                    val limit = minOf(longSide, quality.maxLongSide ?: longSide)
                    val options = BitmapFactory.Options().apply {
                        inSampleSize = 1
                        while (longSide / inSampleSize / 2 >= limit) inSampleSize *= 2
                    }
                    val decoded = BitmapFactory.decodeFile(source.path, options)
                        ?: throw ToolError.Corrupt("Cannot decode ${source.name}")
                    try {
                        val ratio = limit.toDouble() / longSide
                        val scaled = decoded.scale(
                            (width * ratio).roundToInt().coerceAtLeast(1),
                            (height * ratio).roundToInt().coerceAtLeast(1),
                        )
                        try {
                            LosslessFactory.createFromImage(doc, scaled)
                        } finally {
                            if (scaled !== decoded) scaled.recycle()
                        }
                    } finally {
                        decoded.recycle()
                    }
                }
                doc.addPage(page)
                PDPageContentStream(doc, page).use {
                    it.drawImage(image, position.x, position.y, position.w, position.h)
                }
            }
            writeNewFile(out, doc::save)
            ToolResult(out, images.size)
        }
    }
}
