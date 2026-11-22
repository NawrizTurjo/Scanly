package dev.turjo.paperless.feature.tools

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import dev.turjo.paperless.core.imaging.OpenCv
import dev.turjo.paperless.core.imaging.PageProcessor
import dev.turjo.paperless.core.imaging.ProcessParams
import dev.turjo.paperless.core.imaging.detectOnFile
import dev.turjo.paperless.core.pdf.PageSizeOption
import dev.turjo.paperless.core.pdf.QualityPreset
import dev.turjo.paperless.core.pdf.tools.ImagesToPdf
import dev.turjo.paperless.core.pdf.tools.ToolError
import dev.turjo.paperless.core.pdf.tools.ToolResult
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal const val ENHANCE_OPTION = "Enhance"

internal enum class ImageEnhancement(val label: Int, val filter: String?) {
    DOCUMENT(R.string.tools_enhance_document, "DOCUMENT"),
    COLOUR(R.string.tools_enhance_colour, "AUTO"),
    BLACK_AND_WHITE(R.string.tools_enhance_bw, "BW"),
    GRAYSCALE(R.string.tools_enhance_gray, "GRAY"),
    OFF(R.string.tools_enhance_off, null),
}

internal fun Options.imageEnhancement(): ImageEnhancement =
    ImageEnhancement.valueOf(getOrDefault(ENHANCE_OPTION, ImageEnhancement.DOCUMENT.name))

internal suspend fun imagesToPdf(
    context: Context,
    inputs: List<Input>,
    enhancement: ImageEnhancement,
    pageSize: PageSizeOption,
    quality: QualityPreset,
    out: File,
    onEnhancing: (Int, Int) -> Unit,
): ToolResult = withContext(Dispatchers.Default) {
    val filter = enhancement.filter
    if (filter == null) {
        inputs.forEachIndexed { index, input ->
            ensureActive()
            readImage(context, input, index) { imageMimeType(input.file) }
        }
        return@withContext ImagesToPdf.convert(inputs.map { it.file }, pageSize, quality, out)
    }

    OpenCv.ensureLoaded()
    val temp = Files.createTempDirectory(out.absoluteFile.toPath().parent, "enhance-").toFile()
    val processor = PageProcessor()
    try {
        val pages = inputs.mapIndexed { index, input ->
            ensureActive()
            onEnhancing(index + 1, inputs.size)
            val converted = File(temp, "source.jpg")
            val processed = File(temp, "page-${index + 1}.jpg")
            try {
                readImage(context, input, index) {
                    val jpeg = if (imageMimeType(input.file) == "image/jpeg") input.file
                        else convertToJpeg(input.file, converted)
                    val quad = detectOnFile(jpeg).quad
                    processor.process(jpeg, ProcessParams(
                        quad = quad,
                        rotation = 0,
                        filter = filter,
                        brightness = 0f,
                        contrast = 0f,
                        shadowFix = true,
                        glareFix = false,
                        snapToPaper = true,
                        deskew = true,
                    ), processed)
                }
            } finally {
                converted.delete()
            }
            processed
        }
        ensureActive()
        ImagesToPdf.convert(pages, pageSize, quality, out)
    } finally {
        temp.deleteRecursively()
    }
}

private fun imageMimeType(source: File): String {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(source.path, bounds)
    require(bounds.outWidth > 0 && bounds.outHeight > 0)
    return requireNotNull(bounds.outMimeType)
}

private fun convertToJpeg(source: File, destination: File): File {
    val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(source)) { decoder, _, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }
    try {
        destination.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
    } finally {
        bitmap.recycle()
    }
    return destination
}

private inline fun <T> readImage(context: Context, input: Input, index: Int, read: () -> T): T =
    try {
        read()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        throw ToolError.Invalid(context.getString(R.string.tools_unreadable_image, index + 1, input.name))
            .apply { initCause(error) }
    }
