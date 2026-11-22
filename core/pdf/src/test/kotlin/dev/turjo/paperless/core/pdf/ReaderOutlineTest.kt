package dev.turjo.paperless.core.pdf

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitWidthDestination
import com.tom_roush.pdfbox.text.PDFTextStripper
import dev.turjo.paperless.core.model.OutlineNode
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@ConscryptMode(ConscryptMode.Mode.OFF)
class ReaderOutlineTest {
    @get:Rule val temporary = TemporaryFolder()
    private val fixtures = File(System.getProperty("paperless.testData"), "pdf")

    @Before
    fun initializePdfBox() = PdfBox.ensureInit(ApplicationProvider.getApplicationContext<Context>())

    @Test
    fun outlineMatchesTruthAndEditsPreservePagesLinksAndText() = runBlocking {
        val name = "textbook-outline.pdf"
        val input = File(fixtures, name)
        val outline = Outlines.read(input)
        assertEquals(truth(name, "outline"), flatten(outline))

        val edited = listOf(
            outline[1].copy(title = "Renamed chapter"),
            OutlineNode("New bookmark", 0, listOf(outline[0].copy(children = outline[0].children.reversed()))),
        )
        val output = File(temporary.root, "edited.pdf")
        Outlines.write(input, edited, output)
        assertEquals(edited, Outlines.read(output))

        PDDocument.load(input).use { before ->
            PDDocument.load(output).use { after ->
                assertEquals(before.numberOfPages, after.numberOfPages)
                assertEquals(PDFTextStripper().getText(before), PDFTextStripper().getText(after))
                assertEquals(pageLinks(before), pageLinks(after))
                for (index in 0 until before.numberOfPages) {
                    val source = before.getPage(index)
                    val result = after.getPage(index)
                    assertEquals(source.mediaBox.toString(), result.mediaBox.toString())
                    assertEquals(source.cropBox.toString(), result.cropBox.toString())
                    assertEquals(source.rotation, result.rotation)
                    assertTrue(source.contents.use { it.readBytes() }.contentEquals(result.contents.use { it.readBytes() }))
                }
                assertTrue(after.documentCatalog.documentOutline.firstChild.destination is PDPageFitWidthDestination)
            }
        }
    }

    @Test
    fun chapterSuggestionsMeetHeadingAndLevelTargets() = runBlocking {
        val name = "textbook-no-outline.pdf"
        val expected = truth(name, "headings").associateBy { it.titleKey }
        val actual = flatten(ChapterSuggester.suggest(File(fixtures, name)))
        val matches = actual.filter { it.titleKey in expected }
        val recall = matches.size.toDouble() / expected.size
        val falseRate = (actual.size - matches.size).toDouble() / actual.size.coerceAtLeast(1)
        val correctLevels = matches.count { expected.getValue(it.titleKey).level == it.level }
        val levelAccuracy = correctLevels.toDouble() / expected.size
        println("Chapter suggestions: recall=$recall (${matches.size}/${expected.size}), false rate=$falseRate, level accuracy=$levelAccuracy ($correctLevels/${expected.size})")
        assertTrue("Recall: $recall", recall >= 0.9)
        assertTrue("False positive rate: $falseRate", falseRate <= 0.1)
        assertTrue("Level accuracy: $levelAccuracy", levelAccuracy >= 0.8)
        assertTrue(ChapterSuggester.suggest(File(fixtures, "image-only-scan.pdf")).isEmpty())
    }

    private fun truth(name: String, field: String): List<Entry> {
        val items = JSONObject(File(fixtures, "truth.json").readText())
            .getJSONObject("files").getJSONObject(name).getJSONArray(field)
        return List(items.length()) { index ->
            val item = items.getJSONObject(index)
            Entry(item.getString("title"), item.getInt("page") - 1, item.getInt("level"))
        }
    }

    private fun flatten(nodes: List<OutlineNode>, level: Int = 1): List<Entry> =
        nodes.flatMap { listOf(Entry(it.title, it.page, level)) + flatten(it.children, level + 1) }

    private fun pageLinks(document: PDDocument) = document.pages.map { page ->
        page.annotations.filterIsInstance<PDAnnotationLink>().map {
            val target = (it.destination as PDPageDestination).retrievePageNumber()
            it.rectangle.toString() to target
        }
    }

    private data class Entry(val title: String, val page: Int, val level: Int) {
        val titleKey = title.replace(Regex("""\s+"""), " ") to page
    }
}
