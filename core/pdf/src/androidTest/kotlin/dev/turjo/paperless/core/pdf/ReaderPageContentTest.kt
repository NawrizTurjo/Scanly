package dev.turjo.paperless.core.pdf

import android.content.Context
import android.graphics.PointF
import android.net.Uri
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import dev.turjo.paperless.core.model.PageRect
import dev.turjo.paperless.core.model.PdfKey
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 35)
class ReaderPageContentTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val assets = InstrumentationRegistry.getInstrumentation().context.assets
    private val directory = Files.createTempDirectory(context.cacheDir.toPath(), "reader-pages-").toFile()
    private val manager = AndroidPdfSessionManager(context, { key -> Uri.fromFile(File(directory, key.value)) })
    private val truth = JSONObject(assets.open("truth.json").bufferedReader().use { it.readText() })
        .getJSONObject("files")

    @After
    fun cleanup() {
        for (name in truth.keys()) manager.release(PdfKey(name))
        directory.deleteRecursively()
    }

    @Test
    fun searchesEveryReadableFixtureWithSearchTruth() = runBlocking<Unit> {
        var queries = 0
        var hits = 0
        for (name in truth.keys()) {
            val expectedFile = truth.getJSONObject(name)
            if (expectedFile.optBoolean("corrupt") || expectedFile.has("password") || name == "encrypted-aes256.pdf") continue
            val queriesForFile = expectedFile.optJSONObject("search") ?: continue
            val document = open(name)
            assertTrue(document.search(" ").isEmpty())
            if (!expectedFile.optBoolean("has_text")) assertTrue(document.pageText(0).isBlank())
            for (query in queriesForFile.keys()) {
                val expected = queriesForFile.getJSONObject(query)
                val pages = expected.getJSONArray("pages")
                val actual = document.search(if (query == "velocity") "VELOCITY" else query)
                assertEquals("$name: $query count", expected.getInt("total_hits"), actual.size)
                assertEquals("$name: $query pages", List(pages.length()) { pages.getInt(it) - 1 },
                    actual.map { it.page }.distinct())
                assertTrue(actual.all { it.rects.isNotEmpty() })
                queries++
                hits += actual.size
            }
        }
        Log.i("ReaderFeatureScores", "Search: $queries queries, $hits hits matched truth")
    }

    @Test
    fun selectionUsesTheSamePageCoordinatesAsSearch() = runBlocking {
        val document = open("single-page.pdf")
        val rect = document.search("momentum").single().rects.single()
        assertTrue(document.pageText(0).contains("momentum"))
        val y = (rect.top + rect.bottom) / 2
        val selected = document.select(0, PointF(rect.left, y), PointF(rect.right, y))
        assertNotNull(selected)
        assertTrue(selected!!.first.contains("momentum", ignoreCase = true))
        assertTrue(selected.second.any { iou(it, rect) >= 0.8 })
    }

    @Test
    fun linksMatchTruthDestinationsAndBounds() = runBlocking<Unit> {
        val name = "textbook-outline.pdf"
        val document = open(name)
        val fixture = truth.getJSONObject(name)
        val expected = fixture.getJSONArray("links")
        val entries = List(expected.length()) { expected.getJSONObject(it) }.groupBy { it.getInt("from_page") - 1 }
        var links = 0
        for (index in 0 until document.pageCount) {
            val actual = document.links(index).filter { it.targetPage != null }
            val pageLinks = entries[index].orEmpty()
            assertEquals("Page ${index + 1} link count", pageLinks.size, actual.size)
            val height = fixture.getJSONArray("pages").getJSONObject(index).getJSONArray("displayed_size").getDouble(1)
            for (item in pageLinks) {
                val rect = item.getJSONArray("rect")
                val bounds = PageRect(rect.getDouble(0).toFloat(), (height - rect.getDouble(3)).toFloat(),
                    rect.getDouble(2).toFloat(), (height - rect.getDouble(1)).toFloat())
                assertTrue("Page ${index + 1} should link to page ${item.getInt("to_page")} at $bounds, got $actual",
                    actual.any { it.targetPage == item.getInt("to_page") - 1 && it.bounds.any { box -> iou(box, bounds) >= 0.9 } })
                links++
            }
        }
        Log.i("ReaderFeatureScores", "Internal links: $links/${expected.length()} destinations and bounds matched")
    }

    @Test
    fun detectedMarginsReachTruthIou() = runBlocking {
        val name = "wide-margins.pdf"
        val document = open(name)
        val fixture = truth.getJSONObject(name)
        val expected = fixture.getJSONArray("content_boxes")
        val scores = (0 until expected.length()).map { index ->
            val raw = expected.getJSONArray(index)
            val height = fixture.getJSONArray("pages").getJSONObject(index).getJSONArray("displayed_size").getDouble(1)
            val rect = PageRect(raw.getDouble(0).toFloat(), (height - raw.getDouble(3)).toFloat(),
                raw.getDouble(2).toFloat(), (height - raw.getDouble(1)).toFloat())
            val actual = document.contentBox(index)
            assertEquals(actual, document.contentBox(index))
            iou(rect, actual)
        }
        Log.i("ReaderFeatureScores", "Margin IoU: $scores")
        assertTrue("Margin IoU: $scores", scores.all { it >= 0.9 })
    }

    private suspend fun open(name: String): PdfDocumentHandle {
        val file = File(directory, name)
        file.parentFile?.mkdirs()
        assets.open(name).use { input -> file.outputStream().use { input.copyTo(it) } }
        return manager.open(PdfKey(name))
    }

    private fun iou(a: PageRect, b: PageRect): Double {
        val intersection = (minOf(a.right, b.right) - maxOf(a.left, b.left)).coerceAtLeast(0f) *
            (minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)).coerceAtLeast(0f)
        fun area(rect: PageRect) = (rect.right - rect.left) * (rect.bottom - rect.top)
        return intersection.toDouble() / (area(a) + area(b) - intersection)
    }
}
