package dev.turjo.paperless.core.pdf

import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class ImageExporter(@Suppress("UNUSED_PARAMETER") context: Context) {
    suspend fun exportJpegs(
        pages: List<ExportPage>,
        quality: QualityPreset,
        dir: File,
        baseName: String,
    ): List<File> = export(pages, dir, baseName, "jpg") { source, output ->
        val jpegQuality = quality.jpegQuality
        if (jpegQuality == null) {
            source.copyTo(output, overwrite = true)
        } else {
            JpegRecompressor.recompress(source, quality.maxLongSide, jpegQuality, output)
        }
    }

    suspend fun exportPngs(
        pages: List<ExportPage>,
        quality: QualityPreset,
        dir: File,
        baseName: String,
    ): List<File> = export(pages, dir, baseName, "png") { source, output ->
        encodeImage(source, quality.maxLongSide, Bitmap.CompressFormat.PNG, 100, output)
    }

    private suspend fun export(
        pages: List<ExportPage>,
        dir: File,
        baseName: String,
        extension: String,
        write: (File, File) -> Unit,
    ): List<File> = withContext(Dispatchers.IO) {
        require(baseName.isNotBlank() && '/' !in baseName && '\\' !in baseName)
        if (!dir.mkdirs() && !dir.isDirectory) throw IOException("Cannot create export directory")

        val created = mutableListOf<File>()
        try {
            for ((index, page) in pages.withIndex()) {
                ensureActive()
                val name = if (pages.size == 1) baseName else "${baseName}_${index + 1}"
                val output = File(dir, "$name.$extension")
                if (!output.createNewFile()) throw IOException("Export already exists: ${output.name}")
                created += output
                write(page.jpeg, output)
            }
            ensureActive()
            created
        } catch (failure: Exception) {
            created.forEach { it.delete() }
            throw failure
        }
    }
}
