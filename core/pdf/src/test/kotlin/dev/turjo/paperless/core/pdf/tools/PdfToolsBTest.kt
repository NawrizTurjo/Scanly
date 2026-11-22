package dev.turjo.paperless.core.pdf.tools

import android.content.Context
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceGray
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionGoTo
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionURI
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationText
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDNamedDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination
import com.tom_roush.pdfbox.rendering.PDFRenderer
import com.tom_roush.pdfbox.text.PDFTextStripper
import dev.turjo.paperless.core.pdf.PdfBox
import java.io.File
import java.util.Collections
import java.util.IdentityHashMap
import kotlinx.coroutines.runBlocking
import kotlin.math.abs
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PdfToolsBTest {
    @get:Rule val temporary = TemporaryFolder()
    private val testData = File(requireNotNull(System.getProperty("paperless.testData")))
    private fun fixture(name: String) = File(testData, "pdf/$name")

    @Before
    fun initializePdfBox() = PdfBox.ensureInit(ApplicationProvider.getApplicationContext<Context>())

    @Test
    fun widthsMatchOracleAndKeepPageText() = runBlocking {
        val oracle = JSONObject(requireNotNull(javaClass.getResource("/fixwidth-oracle.json")).readText())
        var total = 0
        var rendered = 0
        for (name in oracle.keys()) {
            val input = if (name.startsWith("7_")) {
                File(testData.parentFile, "tools/pdf-fixwidth/examples/$name")
            } else fixture(name)
            val output = temporary.newFile()
            val result = PageWidthNormalizer.normalize(input, WidthTarget.Common, output)
            val expected = oracle.getJSONArray(name)
            assertEquals(expected.length(), result.pages)
            PDDocument.load(input).use { source ->
                PDDocument.load(output).use { normalized ->
                    for (index in 0 until result.pages) {
                        val page = normalized.getPage(index)
                        val dimensions = expected.getJSONArray(index)
                        assertEquals("$name page ${index + 1} width", dimensions.getDouble(0), page.displayedWidth().toDouble(), 0.5)
                        assertEquals("$name page ${index + 1} height", dimensions.getDouble(1), page.displayedHeight().toDouble(), 0.5)
                        assertEquals(pageText(source, index), pageText(normalized, index))
                        val before = source.getPage(index).displayedWidth()
                        val after = page.displayedWidth()
                        if (abs(before - after) > 0.5f) {
                            assertSameRendering(source, normalized, index, after / before)
                            rendered++
                        }
                        total++
                    }
                }
            }
        }
        assertEquals(26, total)
        assertEquals(8, rendered)
    }

    @Test
    fun normalizationMovesAnnotationsAndCopiesUniformInput() = runBlocking {
        val input = temporary.newFile()
        val output = temporary.newFile()
        PDDocument().use { document ->
            val page = PDPage(PDRectangle(0f, 0f, 400f, 600f))
            page.cropBox = PDRectangle(20f, 30f, 200f, 400f)
            page.rotation = 90
            page.annotations = listOf(PDAnnotationText().apply {
                rectangle = PDRectangle(40f, 50f, 60f, 80f)
            })
            document.addPage(page)
            document.save(input)
        }
        assertEquals(listOf(400f), PageWidthNormalizer.widths(input))
        val result = PageWidthNormalizer.normalize(input, WidthTarget.Custom(200f), output)
        assertEquals("1 of 1 pages scaled", result.note)
        PDDocument.load(output).use { document ->
            val rect = document.getPage(0).annotations.single().rectangle
            assertEquals(listOf(10f, 10f, 30f, 40f), listOf(rect.lowerLeftX, rect.lowerLeftY, rect.width, rect.height))
        }
        val copy = temporary.newFile()
        assertEquals("Already uniform", PageWidthNormalizer.normalize(output, WidthTarget.Common, copy).note)
        assertArrayEquals(output.readBytes(), copy.readBytes())
    }

    @Test
    fun stampingMetadataAndPasswordKeepOutlineLinksAndAnnotations() = runBlocking {
        val input = fixture("textbook-outline.pdf")
        val normalized = temporary.newFile()
        val stamped = temporary.newFile()
        val edited = temporary.newFile()
        val locked = temporary.newFile()
        val restored = temporary.newFile()
        val original = input.readBytes()
        val info = DocInfo("A new title", "New author", "New subject", "New keywords")
        PageWidthNormalizer.normalize(input, WidthTarget.Custom(400f), normalized)
        PageNumbers.stamp(normalized, NumberStyle.PAGE_NUMBER, NumberPosition.BOTTOM_CENTER, 12f, 42, 1..2, stamped)
        PdfMetadata.write(stamped, info, false, edited)
        assertEquals(info, PdfMetadata.read(edited))
        PdfPassword.encrypt(edited, "test password", locked)
        assertTrue(PdfPassword.isEncrypted(locked))
        assertThrows(InvalidPasswordException::class.java) { PDDocument.load(locked).close() }
        PDDocument.load(locked, "test password").use {
            assertEquals(256, it.encryption.length)
            assertEquals(5, it.encryption.version)
        }
        PdfPassword.decrypt(locked, "test password", restored)
        assertFalse(PdfPassword.isEncrypted(restored))
        assertEquals(info, PdfMetadata.read(restored))
        PDDocument.load(input).use { source ->
            for (copy in listOf(normalized, stamped, edited, restored)) {
                PDDocument.load(copy).use { output ->
                    assertEquals(outline(source), outline(output))
                    assertEquals(annotationTypes(source), annotationTypes(output))
                    assertEquals(linkTargets(source), linkTargets(output))
                    for (index in 0 until source.numberOfPages) {
                        val expectedStamp = when {
                            copy == normalized -> ""
                            index == 1 -> "Page 42"
                            index == 2 -> "Page 43"
                            else -> ""
                        }
                        assertEquals(pageText(source, index), pageText(output, index).removeSuffix(expectedStamp).trim())
                        if (expectedStamp.isNotEmpty()) assertTrue(pageText(output, index).endsWith(expectedStamp))
                    }
                }
            }
        }
        assertArrayEquals(original, input.readBytes())
    }

    @Test
    fun decryptsAes256FixtureAndRejectsSameOutput() = runBlocking {
        val input = fixture("encrypted-aes256.pdf")
        val output = temporary.newFile()
        assertTrue(PdfPassword.isEncrypted(input))
        PdfPassword.decrypt(input, "scanly-test", output)
        assertFalse(PdfPassword.isEncrypted(output))
        try {
            PdfMetadata.write(output, null, true, output)
            throw AssertionError("Expected same-file output to be rejected")
        } catch (_: ToolError.Invalid) {
            PDDocument.load(output).use { assertTrue(it.numberOfPages > 0) }
        }
    }

    @Test
    fun clearsInfoAndXmp() = runBlocking {
        val input = temporary.newFile()
        val output = temporary.newFile()
        PDDocument().use { document ->
            document.addPage(PDPage())
            document.documentInformation.producer = "Fixture"
            document.documentCatalog.metadata = com.tom_roush.pdfbox.pdmodel.common.PDMetadata(document)
            document.save(input)
        }
        PdfMetadata.write(input, DocInfo("Ignored"), true, output)
        PDDocument.load(output).use {
            assertNull(it.document.trailer.getItem(COSName.INFO))
            assertNull(it.documentCatalog.metadata)
        }
        assertEquals(DocInfo(), PdfMetadata.read(output))
    }

    @Test
    fun sanitizeRemovesScriptsAndAttachmentsAndKeepsText() = runBlocking {
        val input = fixture("with-js-attachment.pdf")
        val output = temporary.newFile()
        PdfSanitize.sanitize(input, true, output)
        PDDocument.load(input).use { source ->
            PDDocument.load(output).use { sanitized ->
                assertEquals(PDFTextStripper().getText(source), PDFTextStripper().getText(sanitized))
                val seen = Collections.newSetFromMap(IdentityHashMap<COSBase, Boolean>())
                fun check(node: COSBase) {
                    if (!seen.add(node)) return
                    when (node) {
                        is COSDictionary -> {
                            for (key in listOf(COSName.JAVA_SCRIPT, COSName.OPEN_ACTION, COSName.AA, COSName.EMBEDDED_FILES)) {
                                assertNull(node.getDictionaryObject(key))
                            }
                            assertNotEquals("JavaScript", node.getNameAsString(COSName.S))
                            assertNotEquals("FileAttachment", node.getNameAsString(COSName.SUBTYPE))
                            for (key in node.keySet()) node.getDictionaryObject(key)?.let(::check)
                        }
                        is COSArray -> for (index in 0 until node.size()) node.getObject(index)?.let(::check)
                    }
                }
                check(sanitized.documentCatalog.cosObject)
            }
        }
    }

    @Test
    fun grayscaleLeavesNoRgbImagesAndKeepsText() = runBlocking {
        val input = fixture("mixed.pdf")
        val output = temporary.newFile()
        PdfGrayscale.convert(input, output)
        PDDocument.load(input).use { source ->
            PDDocument.load(output).use { converted ->
                assertEquals(PDFTextStripper().getText(source), PDFTextStripper().getText(converted))
                val images = converted.pages.flatMap { page ->
                    page.resources.xObjectNames.mapNotNull { page.resources.getXObject(it) as? PDImageXObject }
                }
                assertTrue(images.isNotEmpty())
                for (image in images) {
                    assertEquals(PDDeviceGray.INSTANCE, image.colorSpace)
                    assertEquals(COSName.DCT_DECODE, image.cosObject.getDictionaryObject(COSName.FILTER))
                    val bitmap = image.image
                    val pixel = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
                    assertEquals(Color.red(pixel), Color.green(pixel))
                }
            }
        }
    }

    @Test
    fun impositionKeepsVectorTextAndProducesTwoDuplexTestSheets() = runBlocking {
        val output = temporary.newFile()
        val input = fixture("textbook-outline.pdf")
        NUp.layout(input, NUpLayout.PLAIN_2, 2, Flip.LONG, NUpOptions(), output)
        PDDocument.load(input).use { source ->
            PDDocument.load(output).use { imposed ->
                assertEquals((source.numberOfPages + 1) / 2, imposed.numberOfPages)
                assertEquals(outline(source).map { it.first }, outline(imposed).map { it.first })
                assertEquals(outline(source).map { it.second / 2 }, outline(imposed).map { it.second })
                assertEquals(annotationTypes(source).flatten(), annotationTypes(imposed).flatten())
                val page = imposed.getPage(0)
                assertEquals(2, page.resources.xObjectNames.count { page.resources.getXObject(it) is PDFormXObject })
                assertTrue(pageText(imposed, 0).contains(pageText(source, 0).lineSequence().first()))
            }
        }
        val test = temporary.newFile()
        assertEquals(4, NUp.testSheet(NUpLayout.CUT_EACH_SHEET, Flip.LONG, test).pages)
        PDDocument.load(test).use { assertEquals(listOf("1 3", "4 2", "5 7", "8 6"), (0..3).map { index ->
            pageText(it, index).split(Regex("\\s+")).joinToString(" ")
        }) }
    }

    private fun outline(document: PDDocument): List<Pair<String, Int>> = buildList {
        val root = document.documentCatalog.documentOutline ?: return@buildList
        fun visit(node: com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode) {
            for (entry in node.children()) {
                add(entry.title to document.pages.indexOf(entry.findDestinationPage(document)))
                visit(entry)
            }
        }
        visit(root)
    }

    private fun annotationTypes(document: PDDocument): List<List<String>> =
        document.pages.map { page -> page.annotations.map { it.subtype } }

    private fun linkTargets(document: PDDocument): List<List<Any?>> = document.pages.map { page ->
        page.annotations.filterIsInstance<PDAnnotationLink>().map { link ->
            val action = link.action
            when (val destination = link.destination ?: (action as? PDActionGoTo)?.destination) {
                is PDPageDestination -> destination.retrievePageNumber()
                is PDNamedDestination -> destination.namedDestination
                else -> (action as? PDActionURI)?.uri
            }
        }
    }

    private fun assertSameRendering(source: PDDocument, output: PDDocument, index: Int, scale: Float) {
        val original = PDFRenderer(source).apply { annotationsFilter = { false } }
            .renderImageWithDPI(index, 40f * scale)
        val normalized = PDFRenderer(output).apply { annotationsFilter = { false } }
            .renderImageWithDPI(index, 40f)
        try {
            assertTrue(abs(original.width - normalized.width) <= 2)
            assertTrue(abs(original.height - normalized.height) <= 2)
            val width = minOf(original.width, normalized.width)
            val height = minOf(original.height, normalized.height)
            val left = IntArray(width)
            val right = IntArray(width)
            var different = 0
            for (y in 0 until height) {
                original.getPixels(left, 0, width, 0, y, width, 1)
                normalized.getPixels(right, 0, width, 0, y, width, 1)
                for (x in 0 until width) if (abs(Color.red(left[x]) - Color.red(right[x])) > 60) different++
            }
            val fraction = different.toDouble() / (width * height)
            assertTrue("Page ${index + 1}: $fraction of pixels differ", fraction <= 0.02)
        } finally {
            original.recycle()
            normalized.recycle()
        }
    }

    private fun pageText(document: PDDocument, index: Int): String = PDFTextStripper().apply {
        startPage = index + 1
        endPage = index + 1
    }.getText(document).trim()
}
