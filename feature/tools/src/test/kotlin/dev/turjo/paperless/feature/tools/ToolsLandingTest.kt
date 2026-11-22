package dev.turjo.paperless.feature.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

class ToolSearchTest {
    @Test
    fun catalogContainsEveryToolExactlyOnce() {
        assertEquals(Tool.entries.toSet(), ToolCardInfo.entries.map { it.tool }.toSet())
        assertEquals(Tool.entries.size, ToolCardInfo.entries.size)
    }

    @Test
    fun matchesTitlesDescriptionsAndSynonymsIgnoringCaseAndWhitespace() {
        val compress = ToolCardInfo.COMPRESS
        val title = "Compress"
        val description = "Make the file smaller"
        assertTrue(compress.matches("  CoMpReSs   FILE  ", title, description))
        assertTrue(compress.matches("SHRINK size", title, description))
        assertFalse(compress.matches("shrink photo", title, description))
        assertTrue(ToolCardInfo.PASSWORD.matches("encrypt", "Password", "Lock or unlock a PDF"))
        assertTrue(ToolCardInfo.FROM_IMAGES.matches("picture jpg", "Images → PDF", "Photos into a PDF"))
        assertTrue(ToolCardInfo.SPLIT.matches("extract separate", "Split", "Pages or ranges to files"))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ToolsHistoryStoreTest {
    private val preferences = object : ToolsPreferences {
        private val values = mutableMapOf<String, String>()

        override fun read(key: String) = values[key].orEmpty()
        override fun write(key: String, value: String) { values[key] = value }
    }

    @Test
    fun pinsPersistInPinOrderAndRepinningMovesToEnd() {
        val store = ToolsHistoryStore(preferences)
        store.setPinned(Tool.SPLIT, true)
        store.setPinned(Tool.MERGE, true)
        store.setPinned(Tool.SPLIT, true)
        assertEquals(listOf(Tool.SPLIT, Tool.MERGE), store.history.value.pins)

        val restored = ToolsHistoryStore(preferences).apply { load() }
        restored.setPinned(Tool.SPLIT, false)
        restored.setPinned(Tool.SPLIT, true)
        assertEquals(
            listOf(Tool.MERGE, Tool.SPLIT),
            ToolsHistoryStore(preferences).apply { load() }.history.value.pins,
        )
    }

    @Test
    fun recentOutputsPersistInNewestFirstOrderCappedAtEight() {
        val store = ToolsHistoryStore(preferences)
        val outputs = (1..10).map { RecentOutput("$it.pdf", Tool.SPLIT, it.toLong(), "/cache/$it.pdf") }
        store.record(outputs)
        val restored = ToolsHistoryStore(preferences).apply { load() }
        assertEquals(outputs.takeLast(8).reversed(), restored.history.value.recent)
        restored.remove(outputs.last())
        assertEquals(outputs.drop(2).dropLast(1).reversed(), restored.history.value.recent)
    }

    @Test
    fun repeatedActionsDeduplicateByPathOrUriAndKeepTheSavedLocation() {
        val store = ToolsHistoryStore(preferences)
        val original = RecentOutput("merged.pdf", Tool.MERGE, 1, "/cache/merged.pdf")
        val shared = original.copy(time = 2, path = "/exports/merged.pdf")
        val saved = original.copy(time = 3, uri = "content://media/1")
        val opened = original.copy(time = 4)
        store.record(listOf(original, shared, saved, opened))
        assertEquals(listOf(opened.copy(uri = saved.uri)), store.history.value.recent)

        val sameUri = saved.copy(time = 5, sourcePath = "/other/merged.pdf", path = "/other/merged.pdf")
        store.record(listOf(sameUri))
        assertEquals(listOf(sameUri), store.history.value.recent)

        val samePath = original.copy(time = 6, path = sameUri.path)
        store.record(listOf(samePath))
        assertEquals(listOf(samePath.copy(uri = saved.uri)), store.history.value.recent)
    }
}
