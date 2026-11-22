package dev.turjo.paperless.core.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.text.PDFTextStripper
import dev.turjo.paperless.core.model.Annotation
import dev.turjo.paperless.core.model.AnnotationType
import dev.turjo.paperless.core.model.PageRect
import dev.turjo.paperless.core.model.PdfKey
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class AnnotationFlattenerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val assets = InstrumentationRegistry.getInstrumentation().context.assets
    private val directory = Files.createTempDirectory(context.cacheDir.toPath(), "flattened-").toFile()
    private val manager = AndroidPdfSessionManager(context, { key -> Uri.fromFile(File(directory, key.value)) })

    @After
    fun cleanup() {
        directory.listFiles().orEmpty().forEach { manager.release(PdfKey(it.name)) }
        directory.deleteRecursively()
    }

    @Test
    fun placesInkOnEveryRotationAndPreservesText() = runBlocking {
        assertFlattenedInk("rotated-pages.pdf", 6, (0..5).toList())
    }

    @Test
    fun placesInkOnNonconsecutivePagesAndPreservesText() = runBlocking {
        assertFlattenedInk("multipage-15.pdf", 15, listOf(0, 7, 14), unchangedPage = 1)
    }

    @Test
    fun placesMarkupOnOffsetMediaBoxAndKeepsNoteInsidePage() = runBlocking {
        PdfBox.ensureInit(context)
        val input = File(directory, "offset.pdf")
        PDDocument().use { document ->
            repeat(2) { document.addPage(PDPage(PDRectangle(50f, 80f, 200f, 240f)).apply { rotation = 270 }) }
            document.save(input)
        }
        val key = PdfKey(input.name)
        val output = File(directory, "marked-offset.pdf")
        val highlight = Annotation(
            "highlight", key, 0, AnnotationType.HIGHLIGHT, Color.YELLOW, 4f,
            rects = listOf(PageRect(16f, 23f, 81f, 35f)),
        )
        val note = Annotation(
            "note", key, 1, AnnotationType.NOTE, Color.BLUE, 3f,
            points = listOf(PointF(237f, 198f)), note = "Café",
        )
        AnnotationFlattener.flatten(input, listOf(highlight, note), output)
        val before = manager.open(key)
        val after = manager.open(PdfKey(output.name))
        val markupBounds = checkNotNull(changedBounds(before.render(0, 240), after.render(0, 240)))
        assertEquals(16f, markupBounds.left, 2f)
        assertEquals(23f, markupBounds.top, 2f)
        assertEquals(81f, markupBounds.right, 2f)
        assertEquals(35f, markupBounds.bottom, 2f)
        val noteBounds = checkNotNull(changedBounds(before.render(1, 240), after.render(1, 240)))
        assertEquals(240f, noteBounds.right, 2f)
        assertEquals(200f, noteBounds.bottom, 2f)
        assertTrue("Expected the note box to render beside the glyph", noteBounds.left < 180f)
    }

    private suspend fun assertFlattenedInk(
        fixture: String,
        pageCount: Int,
        markedPages: List<Int>,
        unchangedPage: Int? = null,
    ) {
        PdfBox.ensureInit(context)
        val input = File(directory, fixture)
        assets.open(fixture).use { source -> input.outputStream().use { source.copyTo(it) } }
        val original = input.readBytes()
        val inputKey = PdfKey(input.name)
        val output = File(directory, "flattened.pdf")
        val marks = markedPages.map { page ->
            val y = 40f + 3f * page
            Annotation(
                "mark-$page", inputKey, page, AnnotationType.INK, Color.MAGENTA, 4f,
                points = listOf(PointF(25f, y), PointF(75f, y)),
            )
        }
        AnnotationFlattener.flatten(input, marks, output)

        assertArrayEquals(original, input.readBytes())
        val originalText = extractText(input)
        assertTrue("Expected a searchable fixture: $fixture", originalText.isNotBlank())
        assertEquals(originalText, extractText(output))
        val before = manager.open(inputKey)
        val after = manager.open(PdfKey(output.name))
        assertEquals(pageCount, before.pageCount)
        assertEquals(pageCount, after.pageCount)
        for (mark in marks) {
            val page = mark.page
            val pageWidth = before.pageSize(page).width
            val widthPx = pageWidth.roundToInt()
            assertEquals(pageWidth, after.pageSize(page).width, 0.01f)
            val bounds = checkNotNull(changedBounds(before.render(page, widthPx), after.render(page, widthPx))) {
                "$fixture page $page did not render its ink"
            }
            val scale = widthPx / pageWidth
            val y = mark.points.first().y
            assertEquals("$fixture page $page left", 23f, bounds.left / scale, 2f)
            assertEquals("$fixture page $page right", 77f, bounds.right / scale, 2f)
            assertEquals("$fixture page $page top", y - 2f, bounds.top / scale, 2f)
            assertEquals("$fixture page $page bottom", y + 2f, bounds.bottom / scale, 2f)
        }
        if (unchangedPage != null) {
            assertEquals(null, changedBounds(before.render(unchangedPage, 300), after.render(unchangedPage, 300)))
        }
    }

    private fun extractText(file: File) = PDDocument.load(file).use { PDFTextStripper().getText(it) }

    private fun changedBounds(before: Bitmap, after: Bitmap): PageRect? {
        assertEquals(before.width, after.width)
        assertEquals(before.height, after.height)
        val width = before.width
        val oldRow = IntArray(width)
        val newRow = IntArray(width)
        var left = width
        var top = before.height
        var right = -1
        var bottom = -1
        for (y in 0 until before.height) {
            before.getPixels(oldRow, 0, width, 0, y, width, 1)
            after.getPixels(newRow, 0, width, 0, y, width, 1)
            for (x in 0 until width) {
                if (oldRow[x] == newRow[x]) continue
                left = minOf(left, x)
                right = maxOf(right, x)
                top = minOf(top, y)
                bottom = maxOf(bottom, y)
            }
        }
        return if (right == -1) null else PageRect(left.toFloat(), top.toFloat(), right + 1f, bottom + 1f)
    }
}
