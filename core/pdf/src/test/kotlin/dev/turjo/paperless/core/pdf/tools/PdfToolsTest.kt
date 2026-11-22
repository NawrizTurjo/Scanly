package dev.turjo.paperless.core.pdf.tools

import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.tom_roush.pdfbox.pdmodel.PDDestinationNameTreeNode
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDDocumentNameDictionary
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationText
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDNamedDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode
import com.tom_roush.pdfbox.text.PDFTextStripper
import dev.turjo.paperless.core.pdf.PageSizeOption
import dev.turjo.paperless.core.pdf.PdfBox
import dev.turjo.paperless.core.pdf.QualityPreset
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
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PdfToolsTest {
    @get:Rule val temp = TemporaryFolder()

    @Before
    fun initializePdfBox() = PdfBox.ensureInit(ApplicationProvider.getApplicationContext())

    @Test
    fun printLabelsMatchEverySourceAndSheetSide() = runBlocking {
        for (count in listOf(5, 80)) {
            val source = output("blank-$count.pdf")
            PDDocument().use { document ->
                repeat(count) { document.addPage(PDPage()) }
                document.save(source)
            }
            for (layout in NUpLayout.entries) for (flip in Flip.entries) {
                val target = output("labels-$count-$layout-$flip.pdf")
                NUp.layout(source, layout, layout.perSide, flip, NUpOptions(), target)
                val expected = NUp.impose(count, layout, flip).flatMap { sheet ->
                    listOf(sheet.front, sheet.back).filter { it.isNotEmpty() }
                }
                PDDocument.load(target).use { document ->
                    assertEquals(expected.size, document.numberOfPages)
                    val labels = Regex("""p\. \d+/\d+""")
                    val stripper = PDFTextStripper()
                    for ((side, pages) in expected.withIndex()) {
                        stripper.startPage = side + 1
                        stripper.endPage = side + 1
                        val actual = labels.findAll(stripper.getText(document)).map { it.value }.toList()
                        assertEquals("$count $layout $flip side ${side + 1}",
                            pages.filterNotNull().map { "p. ${it + 1}/$count" }, actual)
                    }
                }
            }
            val target = output("unlabelled-$count.pdf")
            NUp.layout(source, NUpLayout.PLAIN_4, 4, Flip.LONG, NUpOptions(pageLabels = false), target)
            PDDocument.load(target).use { assertTrue(PDFTextStripper().getText(it).isBlank()) }
        }
    }

    @Test
    fun mergePreservesOrderAndGroupsOutlines() = runBlocking {
        val inputs = listOf(fixture("multipage-15"), fixture("textbook-outline"), fixture("rotated-pages"))
        val result = PdfMerge.merge(inputs, output("merged.pdf"))
        assertEquals(45, result.pages)
        PDDocument.load(result.output).use { doc ->
            val entries = doc.documentCatalog.documentOutline.children().toList()
            assertEquals(inputs.map { it.name }, entries.map { it.title })
            assertEquals(listOf(0, 15, 39), entries.map { doc.pages.indexOf(it.findDestinationPage(doc)) })
            assertEquals(17, doc.pages.indexOf(entries[1].firstChild.findDestinationPage(doc)))
            assertEquals(listOf(17, 24, 31), linkTargets(doc, 15))
            assertEquals((1..15).toList(), pageWords(doc).take(15))
            assertEquals(listOf(0, 90, 180, 270, 0, 90), doc.pages.toList().takeLast(6).map { it.rotation })
        }
    }

    @Test
    fun splitsAllowOverlappingRangesAndKeepRemainder() = runBlocking {
        val input = fixture("multipage-15")
        val ranges = PdfSplit.byRanges(input, listOf(2..4, 0..2, 14..14), temp.newFolder())
        assertEquals(listOf(3, 3, 1), ranges.map { it.pages })
        assertEquals(listOf(listOf(3, 4, 5), listOf(1, 2, 3), listOf(15)), ranges.map { pageWords(it.output) })
        val chunks = PdfSplit.everyN(input, 4, temp.newFolder())
        assertEquals(listOf(4, 4, 4, 3), chunks.map { it.pages })
        assertEquals((1..15).toList(), chunks.flatMap { pageWords(it.output) })
    }

    @Test
    fun pageEditsUseThePageOrderAtEachStep() = runBlocking {
        val input = fixture("multipage-15")
        val original = input.readBytes()
        val edits = listOf(
            PageOp.Extract(listOf(9, 1, 3)),
            PageOp.Duplicate(1),
            PageOp.Delete(setOf(2)),
            PageOp.InsertBlank(0),
            PageOp.InsertFrom(input, listOf(14, 5), 2),
            PageOp.Rotate(setOf(1, 2), -90),
            PageOp.Reorder(listOf(2, 1, 4, 3, 5, 0)),
        )
        val result = PageEdits.apply(input, edits, output("edited.pdf"))
        assertEquals(listOf(15, 10, 2, 6, 4, null), pageWords(result.output))
        PDDocument.load(result.output).use { doc ->
            assertEquals(listOf(270, 270, 0, 0, 0, 0), doc.pages.map { it.rotation })
            assertEquals(doc.getPage(1).mediaBox.toString(), doc.getPage(5).mediaBox.toString())
        }
        assertArrayEquals(original, input.readBytes())
    }

    @Test
    fun addsRotationToExistingFlags() = runBlocking {
        val result = PageEdits.apply(fixture("rotated-pages"), listOf(PageOp.Rotate(setOf(1, 3), 90)), output("rotated.pdf"))
        PDDocument.load(result.output).use { doc ->
            assertEquals(listOf(0, 180, 180, 0, 0, 90), doc.pages.map { it.rotation })
        }
    }

    @Test
    fun splitRemapsLinksAndBookmarksAndPreservesTextAndMetadata() = runBlocking {
        val input = fixture("textbook-outline")
        val kept = (0..10).toList()
        val result = PdfSplit.byRanges(input, listOf(0..10), temp.newFolder()).single()
        assertPreserved(input, result.output, kept)
    }

    @Test
    fun pageEditsRemapLinksAndBookmarksAndPreserveTextAndMetadata() = runBlocking {
        val input = fixture("textbook-outline")
        val kept = listOf(3, 9, 0, 1, 4)
        val result = PageEdits.apply(input, listOf(PageOp.Extract(kept)), output("outline.pdf"))
        assertPreserved(input, result.output, kept)
    }

    @Test
    fun copiesAnnotationsAndRemapsLinksOnInsertedPages() = runBlocking {
        val input = fixture("textbook-outline")
        val result = PageEdits.apply(fixture("single-page"),
            listOf(PageOp.InsertFrom(input, listOf(0, 2, 14), 1), PageOp.Duplicate(1)), output("inserted.pdf"))
        PDDocument.load(result.output).use { doc ->
            assertEquals(5, doc.numberOfPages)
            assertEquals(listOf(3), linkTargets(doc, 1))
            assertEquals(listOf(3), linkTargets(doc, 2))
        }
    }

    @Test
    fun splitRetainsNamedDestinationsAndUnrelatedAnnotations() = runBlocking {
        val input = output("named.pdf")
        PDDocument.load(fixture("text-vector")).use { doc ->
            val catalog = doc.documentCatalog
            catalog.names = PDDocumentNameDictionary(catalog).apply {
                dests = PDDestinationNameTreeNode().apply {
                    setNames(mapOf(
                        "kept" to PDPageFitDestination().apply { page = doc.getPage(1) },
                        "removed" to PDPageFitDestination().apply { page = doc.getPage(2) },
                    ))
                }
            }
            doc.getPage(0).annotations = listOf(
                PDAnnotationLink().apply { destination = PDNamedDestination("kept") },
                PDAnnotationLink().apply { destination = PDNamedDestination("removed") },
                PDAnnotationText().apply { contents = "Draft note" },
            )
            doc.save(input)
        }
        val part = PdfSplit.byRanges(input, listOf(0..1), temp.newFolder()).single()
        PDDocument.load(part.output).use { doc ->
            assertEquals(listOf(1), linkTargets(doc, 0))
            assertEquals(setOf("kept"), doc.documentCatalog.names.dests.names.keys)
            assertEquals("Draft note", doc.getPage(0).annotations.filterIsInstance<PDAnnotationText>().single().contents)
        }
    }

    @Test
    fun imagesUseSharedLayoutAndHighQualityJpegPassthrough() = runBlocking {
        val jpeg = image("landscape.jpg", 200, 100, Bitmap.CompressFormat.JPEG)
        val png = image("portrait.png", 100, 200, Bitmap.CompressFormat.PNG)
        val result = ImagesToPdf.convert(listOf(jpeg, png), PageSizeOption.A4, QualityPreset.HIGH, output("images.pdf"))
        assertEquals(2, result.pages)
        PDDocument.load(result.output).use { doc ->
            assertEquals(listOf(842f, 595f), doc.pages.map { it.mediaBox.width })
            assertEquals(listOf(595f, 842f), doc.pages.map { it.mediaBox.height })
            val resources = doc.getPage(0).resources
            val embedded = resources.getXObject(resources.xObjectNames.single()) as PDImageXObject
            assertArrayEquals(jpeg.readBytes(), embedded.cosObject.createRawInputStream().use { it.readBytes() })
        }
    }

    @Test
    fun compressionShrinksScansAndCopiesVectorDocuments() = runBlocking {
        val scan = fixture("image-only-scan")
        val result = PdfCompress.compress(scan, QualityPreset.SMALL, output("scan.pdf"))
        assertTrue(result.output.length() < scan.length() * 0.95)
        assertTrue(result.note.orEmpty().contains("→"))
        PDDocument.load(result.output).use { doc ->
            assertEquals(PDDocument.load(scan).use { it.numberOfPages }, doc.numberOfPages)
            for (page in doc.pages) {
                val image = page.resources.getXObject(page.resources.xObjectNames.single()) as PDImageXObject
                assertTrue(maxOf(image.width, image.height) <= 1400)
            }
        }
        val vector = fixture("text-vector")
        val compact = PdfCompress.compress(vector, QualityPreset.SMALL, output("vector.pdf"))
        assertEquals("Already compact", compact.note)
        assertArrayEquals(vector.readBytes(), compact.output.readBytes())
    }

    @Test
    fun errorsHaveTypedResultsAndNeverReplaceAnExistingFile() = runBlocking {
        val out = output("unexpected.pdf")
        val encrypted = runCatching { PdfMerge.merge(listOf(fixture("encrypted-aes256")), out) }.exceptionOrNull()
        assertTrue(encrypted?.stackTraceToString(), encrypted is ToolError.Encrypted)
        val broken = File(fixture("single-page").parentFile, "batch-folder/broken.pdf")
        val corrupt = runCatching { PdfCompress.compress(broken, QualityPreset.SMALL, out) }.exceptionOrNull()
        assertTrue(corrupt?.stackTraceToString(), corrupt is ToolError.Corrupt)
        assertTrue(runCatching { PageEdits.apply(fixture("single-page"), listOf(PageOp.Reorder(listOf(1))), out) }
            .exceptionOrNull() is ToolError.Invalid)
        assertTrue(!out.exists())
        val input = fixture("single-page").copyTo(output("original.pdf"))
        val original = input.readBytes()
        assertTrue(runCatching { PdfMerge.merge(listOf(input), input) }.exceptionOrNull() is ToolError.Invalid)
        assertArrayEquals(original, input.readBytes())
    }

    private fun assertPreserved(input: File, out: File, kept: List<Int>) {
        PDDocument.load(input).use { source ->
            PDDocument.load(out).use { result ->
                assertEquals(kept.map { text(source, it) }, result.pages.toList().indices.map { text(result, it) })
                assertEquals(source.documentInformation.title, result.documentInformation.title)
                assertEquals(source.documentInformation.author, result.documentInformation.author)
                for ((newIndex, oldIndex) in kept.withIndex()) {
                    assertEquals(linkTargets(source, oldIndex).filter { it in kept }.map { kept.indexOf(it) },
                        linkTargets(result, newIndex))
                }
                val expected = bookmarkTargets(source).filterValues { it in kept }.mapValues { kept.indexOf(it.value) }
                assertTrue(expected.isNotEmpty())
                assertEquals(expected, bookmarkTargets(result))
            }
        }
    }

    private fun bookmarkTargets(doc: PDDocument): Map<String, Int> = buildMap {
        fun visit(node: PDOutlineNode) {
            for (item in node.children()) {
                item.findDestinationPage(doc)?.let { page ->
                    val index = doc.pages.indexOf(page)
                    assertTrue("Bookmark must target a page in the tree", index >= 0)
                    put(item.title, index)
                }
                visit(item)
            }
        }
        visit(doc.documentCatalog.documentOutline)
    }

    private fun linkTargets(doc: PDDocument, page: Int): List<Int> =
        doc.getPage(page).annotations.filterIsInstance<PDAnnotationLink>().map {
            val destination = it.destination as PDPageDestination
            doc.pages.indexOf(destination.page).also { index -> assertTrue(index >= 0) }
        }

    private fun pageWords(file: File): List<Int?> = PDDocument.load(file).use { pageWords(it) }

    private fun pageWords(doc: PDDocument): List<Int?> = (0 until doc.numberOfPages).map { index ->
        val page = doc.getPage(index)
        val rotation = page.rotation
        page.rotation = 0
        try {
            Regex("pageword-(\\d+)").find(text(doc, index))?.groupValues?.get(1)?.toInt()
        } finally {
            page.rotation = rotation
        }
    }

    private fun text(doc: PDDocument, index: Int): String =
        PDFTextStripper().apply {
            startPage = index + 1
            endPage = index + 1
        }.getText(doc)

    private fun image(name: String, width: Int, height: Int, format: Bitmap.CompressFormat): File =
        output(name).also { file ->
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                file.outputStream().use { assertTrue(bitmap.compress(format, 92, it)) }
            } finally {
                bitmap.recycle()
            }
        }

    private fun output(name: String) = File(temp.root, name)

    private fun fixture(name: String) = File(requireNotNull(System.getProperty("paperless.testData")), "pdf/$name.pdf")
}
