package dev.turjo.paperless.core.pdf

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDTrueTypeFont
import com.tom_roush.pdfbox.pdmodel.font.encoding.WinAnsiEncoding
import com.tom_roush.pdfbox.text.PDFTextStripper
import dev.turjo.paperless.core.ocr.RecognizedWord
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SearchableTextTest {
    @Test
    fun mapsBoxesOnRenderedPortraitAndRotatedLandscapeImages() {
        val portrait = ExportPage(File("portrait.jpg"), 100, 200)
        val word = RecognizedWord("Scan", 90f, 10f, 20f, 40f, 30f)
        assertEquals(PagePlacement(25f, 347f, 60f, 20f),
            placeWord(word, portrait, PagePlacement(5f, 7f, 200f, 400f)))

        val landscape = ExportPage(File("rotated.jpg"), 200, 100)
        val rotated = word.copy(left = 170f, top = 10f, right = 180f, bottom = 40f)
        assertEquals(PagePlacement(345f, 127f, 20f, 60f),
            placeWord(rotated, landscape, PagePlacement(5f, 7f, 400f, 200f)))
    }

    @Test
    fun embeddedWinAnsiLayerExtractsEnglishAndSkipsBengali() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        PdfBox.ensureInit(context)
        PDDocument().use { pdf ->
            context.assets.open("com/tom_roush/pdfbox/resources/ttf/LiberationSans-Regular.ttf").use {
                val font = PDTrueTypeFont.load(pdf, it, WinAnsiEncoding.INSTANCE)
                assertNotNull(font.fontDescriptor.fontFile2)
                assertNotNull(encodableWidth(font, "Café"))
                assertNull(encodableWidth(font, "বাংলা"))
                val page = PDPage()
                pdf.addPage(page)
                val first = RecognizedWord("Café", 98f, 5f, 5f, 40f, 20f)
                val source = ExportPage(File("page.jpg"), 100, 200,
                    listOf(first, first.copy(text = "বাংলা", left = 45f, right = 75f)))
                PDPageContentStream(pdf, page).use { stream ->
                    writeSearchableText(stream, source, PagePlacement(0f, 0f, 100f, 200f), font)
                }
                assertEquals("Café", PDFTextStripper().getText(pdf).trim())
            }
        }
    }
}
