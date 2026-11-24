package dev.turjo.paperless.core.pdf

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.pdfparser.PDFStreamParser
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import dev.turjo.paperless.core.model.Annotation
import dev.turjo.paperless.core.model.AnnotationType
import dev.turjo.paperless.core.model.PageRect
import dev.turjo.paperless.core.model.PdfKey
import dev.turjo.paperless.core.model.SignaturePlacement
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
class SignatureFlattenTest {
    @get:Rule val files = TemporaryFolder()

    @Test
    fun embedsTransparentImageAtTheNormalizedRectOnRotatedCropBox() = runBlocking {
        PdfBox.ensureInit(ApplicationProvider.getApplicationContext())
        val input = files.newFile("source.pdf")
        val output = files.newFile("signed.pdf")
        val png = files.newFile("ink.png")
        val bitmap = Bitmap.createBitmap(20, 10, Bitmap.Config.ARGB_8888)
        try {
            bitmap.setPixel(2, 2, Color.BLUE)
            png.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
        PDDocument().use { pdf ->
            pdf.addPage(PDPage(PDRectangle(500f, 500f)).apply {
                cropBox = PDRectangle(30f, 40f, 200f, 320f)
                rotation = 90
            })
            pdf.save(input)
        }
        val placement = SignaturePlacement("ink", PageRect(0.25f, 0.5f, 0.5f, 0.625f))
        val mark = Annotation("mark", PdfKey(input.name), 0, AnnotationType.SIGNATURE, 0, 0f, signature = placement)
        AnnotationFlattener.flatten(input, listOf(mark), output) {
            assertEquals("ink", it)
            png
        }
        PDDocument.load(output).use { pdf ->
            val page = pdf.getPage(0)
            val image = page.resources.getXObject(page.resources.xObjectNames.single()) as PDImageXObject
            assertEquals(20, image.width)
            assertEquals(10, image.height)
            assertNotNull(image.softMask)

            val parser = PDFStreamParser(page).apply { parse() }
            val tokens = parser.tokens
            val matrices = tokens.indices.filter { (tokens[it] as? Operator)?.name == "cm" }.map { index ->
                tokens.subList(index - 6, index).map { (it as COSNumber).floatValue() }.toFloatArray()
            }
            assertEquals(1, tokens.count { (it as? Operator)?.name == "Do" })
            assertEquals(2, matrices.size)
            assertArrayEquals(floatArrayOf(0f, 1f, 1f, 0f, 30f, 40f), matrices[0], 0.001f)
            assertArrayEquals(floatArrayOf(80f, 0f, 0f, -25f, 80f, 125f), matrices[1], 0.001f)
        }
    }
}
