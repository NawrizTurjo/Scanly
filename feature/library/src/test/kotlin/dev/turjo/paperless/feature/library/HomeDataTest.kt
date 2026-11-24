package dev.turjo.paperless.feature.library

import dev.turjo.paperless.core.model.DocumentSummary
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeDataTest {
    private val expectedDefaults = listOf(
        "scan", "import_images", "tool:FROM_IMAGES", "open_pdf", "tool:MERGE",
        "tool:COMPRESS", "id_card", "book", "all_tools",
    )
    private val catalog = (expectedDefaults + listOf("whiteboard", "tool:SPLIT")).toSet()

    @Test
    fun freshHomeShowsTheDefaultShortcutsInOrder() {
        assertEquals(expectedDefaults, decodeShortcuts(null, catalog))
    }

    @Test
    fun editedShortcutOrderSurvivesEncodingAndReload() {
        val edited = listOf("whiteboard", "tool:SPLIT", "open_pdf", "all_tools")
        assertEquals(edited, decodeShortcuts(encodeShortcuts(edited), catalog))
        assertEquals(listOf("all_tools"), decodeShortcuts(encodeShortcuts(emptyList()), catalog))
    }

    @Test
    fun allToolsIsPinnedLastEvenWithStaleOrDuplicateSavedIds() {
        val saved = "all_tools,book,old_tool,scan,book,all_tools"
        assertEquals(listOf("book", "scan", "all_tools"), decodeShortcuts(saved, catalog))
    }

    @Test
    fun recentDocumentsUseTheLatestOpenOrEditAndKeepOnlySix() {
        val documents = (1..8).map { id ->
            DocumentSummary(
                id.toString(), "Document $id", pageCount = 1, coverThumbPath = null,
                updatedAt = id.toLong(), isFavorite = false,
            )
        }
        val recent = recentDocuments(documents, mapOf("1" to 10L, "8" to 3L, "deleted" to 11L))
        assertEquals(listOf("1", "8", "7", "6", "5", "4"), recent.map { it.document.id })
        assertEquals(listOf(10L, 8L, 7L, 6L, 5L, 4L), recent.map { it.activityAt })
    }
}
