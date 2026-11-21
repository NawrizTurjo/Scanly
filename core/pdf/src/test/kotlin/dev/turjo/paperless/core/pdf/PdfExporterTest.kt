package dev.turjo.paperless.core.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PdfExporterTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var exporter: PdfExporter
    private lateinit var pages: List<ExportPage>

    @Before
    fun setUp() {
        exporter = PdfExporter(ApplicationProvider.getApplicationContext<Context>())
        val warped = File(
            requireNotNull(System.getProperty("paperless.testData")),
            "imaging-goldens/warped",
        )
        pages = listOf("busy-background_03", "clean_01", "id-cards_03").map { name ->
            val png = File(warped, "$name.png")
            val bitmap = requireNotNull(BitmapFactory.decodeFile(png.path)) { "Cannot decode $png" }
            val jpeg = temporaryFolder.newFile("$name.jpg")
            try {
                jpeg.outputStream().use { out ->
                    check(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out))
                }
                ExportPage(jpeg, bitmap.width, bitmap.height)
            } finally {
                bitmap.recycle()
            }
        }
    }

    @Test
    fun highQualityEmbedsOriginalJpegs() {
        val option = PageSizeOption.A4
        val pdf = export(QualityPreset.HIGH, option)

        PDDocument.load(pdf).use { document ->
            assertEquals("Scanned pages", document.documentInformation.title)
            assertEquals("Scanly", document.documentInformation.producer)
            assertEquals(3, document.numberOfPages)
            pages.forEachIndexed { index, source ->
                val page = document.getPage(index)
                val box = PageLayout.pageBox(option, source.widthPx, source.heightPx)
                assertEquals(box.width, page.mediaBox.width, 0f)
                assertEquals(box.height, page.mediaBox.height, 0f)
                val image = page.resources.getXObject(page.resources.xObjectNames.single()) as PDImageXObject
                assertEquals(source.widthPx, image.width)
                assertEquals(source.heightPx, image.height)
                assertEquals(source.jpeg.length(), image.cosObject.length)
            }
        }
    }

    @Test
    fun smallPresetProducesSmallerPdf() {
        val high = export(QualityPreset.HIGH, PageSizeOption.AUTO)
        val small = export(QualityPreset.SMALL, PageSizeOption.AUTO)

        assertTrue("Small PDF: ${small.length()}, high PDF: ${high.length()}", small.length() < high.length())
        PDDocument.load(small).use { document ->
            assertEquals(3, document.numberOfPages)
            for (page in document.pages) {
                val image = page.resources.getXObject(page.resources.xObjectNames.single()) as PDImageXObject
                assertTrue(maxOf(image.height, image.width) <= 1400)
            }
        }
    }

    @Test
    fun imageExportPreservesHighJpegAndResizesSmallPng() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val imageExporter = ImageExporter(context)
        val dir = temporaryFolder.newFolder()
        val source = temporaryFolder.newFile("large.jpg")
        val bitmap = Bitmap.createBitmap(3000, 1500, Bitmap.Config.ARGB_8888)
        try {
            source.outputStream().use { out ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out))
            }
        } finally {
            bitmap.recycle()
        }
        val input = listOf(ExportPage(source, 3000, 1500))

        val jpeg = imageExporter.exportJpegs(input, QualityPreset.HIGH, dir, "scan").single()
        assertArrayEquals(source.readBytes(), jpeg.readBytes())

        val png = imageExporter.exportPngs(input, QualityPreset.SMALL, dir, "scan").single()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(png.path, bounds)
        assertEquals("image/png", bounds.outMimeType)
        assertEquals(1400, bounds.outWidth)
        assertEquals(700, bounds.outHeight)
    }

    private fun export(quality: QualityPreset, option: PageSizeOption): File {
        val pdf = temporaryFolder.newFile("${quality.name}.pdf")
        pdf.outputStream().use { out ->
            runBlocking { exporter.export(pages, option, quality, out, title = "Scanned pages") }
        }
        return pdf
    }
}
