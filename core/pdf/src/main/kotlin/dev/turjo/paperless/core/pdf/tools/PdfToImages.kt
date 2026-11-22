package dev.turjo.paperless.core.pdf.tools

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.core.graphics.createBitmap
import java.io.File
import java.io.IOException
import kotlin.math.ceil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

enum class ImageFormat { JPEG, PNG }

object PdfToImages {
    suspend fun convert(input: File, dpi: Int, format: ImageFormat, outDir: File): List<File> =
        withContext(Dispatchers.IO) {
            requireTool(dpi > 0, "DPI must be positive")
            openPdf(input).close()
            outputDirectory(outDir)
            val extension = if (format == ImageFormat.JPEG) "jpg" else "png"
            val encoding = if (format == ImageFormat.JPEG) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG
            val descriptor = ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = try {
                PdfRenderer(descriptor)
            } catch (failure: Exception) {
                try {
                    descriptor.close()
                } catch (error: IOException) {
                    failure.addSuppressed(error)
                }
                throw when (failure) {
                    is SecurityException -> ToolError.Encrypted()
                    is IOException, is IllegalArgumentException ->
                        ToolError.Corrupt("Cannot render ${input.name}: ${failure.message}").apply { initCause(failure) }
                    else -> failure
                }
            }
            renderer.use {
                writeSeries { created ->
                    (0 until renderer.pageCount).map { index ->
                        ensureActive()
                        val number = (index + 1).toString().padStart(3, '0')
                        val out = File(outDir, "${input.nameWithoutExtension}-p$number.$extension")
                        renderer.openPage(index).use { page ->
                            val scale = dpi / 72.0
                            val width = ceil(page.width * scale)
                            val height = ceil(page.height * scale)
                            requireTool(width > 0 && height > 0 && width * height <= Int.MAX_VALUE / 4,
                                "Page is too large at this DPI")
                            val bitmap = createBitmap(width.toInt(), height.toInt())
                            try {
                                bitmap.eraseColor(Color.WHITE)
                                val transform = Matrix().apply { setScale(scale.toFloat(), scale.toFloat()) }
                                page.render(bitmap, null, transform, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                                writeNewFile(out) { temp ->
                                    temp.outputStream().use { stream ->
                                        if (!bitmap.compress(encoding, 92, stream)) throw IOException("Cannot encode ${out.name}")
                                    }
                                }
                                created += out
                            } finally {
                                bitmap.recycle()
                            }
                        }
                        out
                    }
                }
            }
        }
}
