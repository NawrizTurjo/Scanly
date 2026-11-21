package dev.turjo.paperless.core.pdf

import android.content.Context
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import java.io.File
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class PdfExporter(context: Context) {
    private val appContext = context.applicationContext

    suspend fun export(
        pages: List<ExportPage>,
        option: PageSizeOption,
        quality: QualityPreset,
        out: OutputStream,
        title: String? = null,
    ): Unit = withContext(Dispatchers.IO) {
        PdfBox.ensureInit(appContext)
        val scratch = MemoryUsageSetting.setupTempFileOnly().setTempDir(appContext.cacheDir)
        PDDocument(scratch).use { document ->
            document.documentInformation.title = title ?: "Scanly"
            document.documentInformation.producer = "Scanly"

            for (source in pages) {
                ensureActive()
                val box = PageLayout.pageBox(option, source.widthPx, source.heightPx)
                val placement = PageLayout.placement(option, source.widthPx, source.heightPx)
                val page = PDPage(PDRectangle(box.width, box.height))
                document.addPage(page)

                val jpegQuality = quality.jpegQuality
                val temp = jpegQuality?.let { File.createTempFile("page-", ".jpg", appContext.cacheDir) }
                try {
                    if (temp != null) {
                        JpegRecompressor.recompress(source.jpeg, quality.maxLongSide, jpegQuality, temp)
                    }
                    val image = (temp ?: source.jpeg).inputStream().use {
                        JPEGFactory.createFromStream(document, it)
                    }
                    PDPageContentStream(document, page).use {
                        it.drawImage(image, placement.x, placement.y, placement.w, placement.h)
                    }
                } finally {
                    temp?.delete()
                }
            }
            ensureActive()
            // PdfBox closes its stream when saving. The caller owns the destination.
            document.save(object : OutputStream() {
                override fun write(value: Int) = out.write(value)
                override fun write(bytes: ByteArray, offset: Int, length: Int) =
                    out.write(bytes, offset, length)
                override fun flush() = out.flush()
            })
            out.flush()
        }
    }
}
