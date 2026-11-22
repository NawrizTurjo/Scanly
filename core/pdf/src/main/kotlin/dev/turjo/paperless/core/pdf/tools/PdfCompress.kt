package dev.turjo.paperless.core.pdf.tools

import androidx.core.graphics.scale
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceGray
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import dev.turjo.paperless.core.pdf.QualityPreset
import java.io.File
import java.io.IOException
import java.util.Locale
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

object PdfCompress {
    suspend fun compress(input: File, preset: QualityPreset, out: File): ToolResult = withContext(Dispatchers.IO) {
        openPdf(input).use { document ->
            val compressor = ImageCompressor(document, preset)
            for (page in document.pages) {
                ensureActive()
                page.resources?.let { compressor.visit(it) }
            }
            val originalSize = input.length()
            val note = writeNewFile(out) { temp ->
                if (compressor.changed) document.save(temp)
                if (compressor.changed && temp.length() <= originalSize * 0.95) {
                    "${sizeLabel(originalSize)} → ${sizeLabel(temp.length())}"
                } else {
                    input.copyTo(temp, overwrite = true)
                    "Already compact"
                }
            }
            ToolResult(out, document.numberOfPages, note)
        }
    }

    private fun sizeLabel(bytes: Long): String = when {
        bytes >= 1_000_000 -> String.format(Locale.ROOT, "%.1f MB", bytes / 1_000_000.0)
        bytes >= 1_000 -> String.format(Locale.ROOT, "%.1f KB", bytes / 1_000.0)
        else -> "$bytes B"
    }

    private class ImageCompressor(private val document: PDDocument, private val preset: QualityPreset) {
        private val visited = mutableSetOf<COSBase>()
        private val replacements = mutableMapOf<COSBase, PDImageXObject?>()
        var changed = false
            private set

        suspend fun visit(resources: PDResources) {
            if (!visited.add(resources.cosObject)) return
            for (name in resources.xObjectNames.toList()) {
                coroutineContext.ensureActive()
                val xObject = resources.getXObject(name)
                when (xObject) {
                    is PDFormXObject -> xObject.resources?.let { visit(it) }
                    is PDImageXObject -> {
                        val key = xObject.cosObject
                        if (key !in replacements) replacements[key] = recompress(xObject)
                        replacements[key]?.let {
                            resources.put(name, it)
                            changed = true
                        }
                    }
                }
            }
        }

        private fun recompress(image: PDImageXObject): PDImageXObject? {
            val original = image.cosObject
            val filter = when (val filters = original.getDictionaryObject(COSName.FILTER)) {
                is COSArray -> if (filters.size() == 0) null else filters.getObject(filters.size() - 1)
                else -> filters
            }
            if (filter != COSName.DCT_DECODE && filter != COSName.FLATE_DECODE) return null
            if (minOf(image.width, image.height) < 64 || image.isStencil ||
                original.containsKey(COSName.MASK) || original.containsKey(COSName.SMASK) ||
                original.getInt("SMaskInData", 0) != 0
            ) return null
            val colorSpace = image.colorSpace
            if (colorSpace !is PDDeviceRGB && colorSpace !is PDDeviceGray) return null
            val bitmap = try {
                image.image
            } catch (_: IOException) {
                return null
            } ?: return null
            try {
                val longSide = maxOf(bitmap.width, bitmap.height)
                val ratio = minOf(longSide, preset.maxLongSide ?: longSide).toDouble() / longSide
                val scaled = bitmap.scale(
                    (bitmap.width * ratio).roundToInt().coerceAtLeast(1),
                    (bitmap.height * ratio).roundToInt().coerceAtLeast(1),
                )
                try {
                    val replacement = JPEGFactory.createFromImage(document, scaled, (preset.jpegQuality ?: 92) / 100f)
                    if (replacement.cosObject.length >= original.length) return null
                    for (key in listOf(COSName.INTERPOLATE, COSName.INTENT, COSName.OC, COSName.METADATA)) {
                        original.getItem(key)?.let { replacement.cosObject.setItem(key, it) }
                    }
                    return replacement
                } finally {
                    if (scaled !== bitmap) scaled.recycle()
                }
            } finally {
                bitmap.recycle()
            }
        }
    }
}
