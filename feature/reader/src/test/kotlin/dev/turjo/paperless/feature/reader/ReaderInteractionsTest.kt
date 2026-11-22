package dev.turjo.paperless.feature.reader

import dev.turjo.paperless.core.model.OutlineNode
import dev.turjo.paperless.core.model.PageRect
import dev.turjo.paperless.core.model.TextMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReaderInteractionsTest {
    @Test
    fun pageTapsToggleImmersiveChrome() {
        val immersive = ReaderChrome.VISIBLE.pageTap(annotating = false)
        assertEquals(ReaderChrome.IMMERSIVE, immersive)
        assertEquals(ReaderChrome.VISIBLE, immersive.pageTap(annotating = false))
    }

    @Test
    fun lockedPageTapKeepsChromeHiddenUntilUnlock() {
        val locked = ReaderChrome.LOCKED.pageTap(annotating = false)
        assertEquals(ReaderChrome.LOCKED, locked)
        assertEquals(true, locked.immersive)
        assertEquals(ReaderChrome.VISIBLE, locked.unlock())
    }

    @Test
    fun pageTapsInAnnotateModeLeaveChromeVisible() {
        assertEquals(ReaderChrome.VISIBLE, ReaderChrome.VISIBLE.pageTap(annotating = true))
    }

    @Test
    fun annotationCoordinatesRoundTripAtOneTwoAndFourTimesZoom() {
        for ((width, height) in listOf(600f to 800f, 800f to 600f)) {
            val crop = PageRect(13f, 17f, width - 23f, height - 29f)
            for (zoom in listOf(1f, 2f, 4f)) {
                val screenWidth = 480f * zoom
                val screenHeight = screenWidth * (crop.bottom - crop.top) / (crop.right - crop.left)
                val transform = PageTransform(crop, screenWidth, screenHeight, -57f * (zoom - 1), -91f)
                for ((x, y) in listOf(
                    crop.left to crop.top,
                    width * 0.434f to height * 0.792f,
                    crop.right to crop.bottom,
                )) {
                    val mapped = transform.map(PageRect(x, y, x, y))
                    val (restoredX, restoredY) = transform.unmap(mapped.left, mapped.top)
                    assertEquals(x, restoredX, 0.01f)
                    assertEquals(y, restoredY, 0.01f)
                }
            }
        }
    }

    @Test
    fun mapsAtZoomWithPanAndInvertsPointerCoordinates() {
        val transform = PageTransform(PageRect(10f, 20f, 110f, 220f), 200f, 400f, -30f, 15f)
        assertEquals(PageRect(-10f, 55f, 50f, 95f), transform.map(PageRect(20f, 40f, 50f, 60f)))
        assertEquals(20f to 40f, transform.unmap(-10f, 55f))
    }

    @Test
    fun usesDisplayedLandscapeCoordinatesForRotatedPages() {
        // A rotated 600 x 800 PDF arrives as 800 x 600 with bounds already rotated.
        val transform = PageTransform(PageRect(0f, 0f, 800f, 600f), 400f, 300f)
        assertEquals(PageRect(350f, 10f, 390f, 30f), transform.map(PageRect(700f, 20f, 780f, 60f)))
    }

    @Test
    fun searchWrapsInBothDirectionsAndPreservesIndependentTabQueries() {
        val first = SearchState("first", listOf(TextMatch(1, emptyList()), TextMatch(4, emptyList())))
        val second = SearchState("second")
        val tabs = mapOf("left" to first, "right" to second)
        val updated = tabs + ("left" to first.step(-1))
        assertEquals(4, updated.getValue("left").current?.page)
        assertEquals(first, updated.getValue("left").step(1))
        assertEquals(second, updated.getValue("right").step(1))
    }

    @Test
    fun internalLinksReturnInReverseOrder() {
        val history = LinkHistory().follow(3).follow(12)
        assertEquals(12, history.previous)
        assertEquals(3, history.back().previous)
        assertNull(history.back().back().previous)
    }

    @Test
    fun movesAndNestsEntireBookmarkBranches() {
        val child = OutlineNode("B.1", 2)
        val a = OutlineNode("A", 0)
        val b = OutlineNode("B", 1, listOf(child))
        val c = OutlineNode("C", 3)
        val rows = listOf(a, b, c).flattenOutline()
        assertEquals(listOf(b, a, c), rows.moveBranch(1, -1).toOutline())
        assertEquals(listOf(a, c, b), rows.moveBranch(1, 1).toOutline())
        val nested = rows.changeLevel(1, 1)
        assertEquals(listOf(a.copy(children = listOf(b)), c), nested.toOutline())
        assertEquals(rows, nested.changeLevel(1, -1))
    }

    @Test
    fun outdentMovesBranchAfterRemainingSiblings() {
        val a = OutlineNode("A", 0)
        val b = OutlineNode("B", 1)
        val root = OutlineNode("Root", 0, listOf(a, b))
        assertEquals(listOf(root.copy(children = listOf(b)), a),
            listOf(root).flattenOutline().changeLevel(1, -1).toOutline())
    }

    @Test
    fun mergesSelectedSuggestionsWithoutDuplicateBookmarks() {
        val existing = OutlineRow("Chapter I", 0, 0)
        val suggestions = listOf(OutlineRow(" chapter i ", 0, 0), OutlineRow("Section", 2, 1),
            OutlineRow("Chapter II", 4, 0), OutlineRow("Details", 5, 1))
        assertEquals(
            listOf(OutlineNode("Chapter I", 0), OutlineNode("Section", 2),
                OutlineNode("Chapter II", 4, listOf(OutlineNode("Details", 5)))),
            listOf(existing).mergeSuggestions(suggestions).toOutline(),
        )
    }
}
