package dev.turjo.paperless.core.imaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IdCardLayoutTest {
    @Test
    fun presetsFitOneThroughSixCardsAndKeepTheLegacyPairRects() {
        for (arrangement in IdCardLayout.Arrangement.entries) {
            for (size in IdCardLayout.Size.entries) {
                for (gapMm in listOf(0, 6, 15)) {
                    val layout = IdCardLayout(arrangement, size, gapMm)
                    for (count in 1..6) {
                        val rects = IdCardComposer.cardRects(layout, List(count) { 1.586f })
                        assertEquals(count, rects.size)
                        rects.forEachIndexed { index, rect ->
                            assertTrue(rect.left >= 0 && rect.top >= 0)
                            assertTrue(rect.right <= 2480 && rect.bottom <= 3508)
                            for (other in rects.drop(index + 1)) {
                                assertTrue(rect.right <= other.left || other.right <= rect.left ||
                                    rect.bottom <= other.top || other.bottom <= rect.top)
                            }
                        }
                    }
                    val gap = IdCardComposer.gapPixels(gapMm)
                    val stacked = arrangement == IdCardLayout.Arrangement.STACKED
                    val width = when {
                        size == IdCardLayout.Size.ACTUAL -> 1011
                        stacked -> 1984
                        else -> (1984 - gap) / 2
                    }
                    val height = kotlin.math.round(width * 638.0 / 1011).toInt()
                    val pairWidth = if (stacked) width else width * 2 + gap
                    val front = CardRect((2480 - pairWidth) / 2, 118, width, height)
                    val back = if (stacked) front.copy(top = 118 + height + gap)
                        else front.copy(left = front.left + width + gap)
                    assertEquals(front to back, IdCardComposer.cardRects(layout))
                }
            }
        }
    }

    @Test
    fun cardsAreCenteredWithTheRequestedGapAndSize() {
        for (arrangement in IdCardLayout.Arrangement.entries) {
            for (size in IdCardLayout.Size.entries) {
                for ((gapMm, gapPx) in listOf(0 to 0, 6 to 71, 15 to 177)) {
                    val layout = IdCardLayout(arrangement, size, gapMm)
                    val (front, back) = IdCardComposer.cardRects(layout)
                    val stacked = arrangement == IdCardLayout.Arrangement.STACKED
                    val pairWidth = if (stacked) front.width else front.width * 2 + gapPx
                    assertTrue(IdCardComposer.PAGE_WIDTH - 2 * front.left - pairWidth in 0..1)
                    assertTrue(front.top <= IdCardComposer.PAGE_HEIGHT / 3)
                    assertEquals(front.width, back.width)
                    assertEquals(front.height, back.height)
                    if (stacked) {
                        assertEquals(front.left, back.left)
                        assertEquals(gapPx, back.top - front.top - front.height)
                        assertTrue((front.top + back.top + back.height) / 2 < IdCardComposer.PAGE_HEIGHT / 3 ||
                            size == IdCardLayout.Size.LARGE)
                    } else {
                        assertEquals(front.top, back.top)
                        assertEquals(gapPx, back.left - front.left - front.width)
                    }
                    if (size == IdCardLayout.Size.ACTUAL) {
                        assertEquals(1011, front.width)
                        assertEquals(638, front.height)
                    } else {
                        assertTrue(IdCardComposer.PAGE_WIDTH * 4 / 5 - pairWidth in 0..1)
                    }
                }
            }
        }
    }

    @Test
    fun freePlacementClampsWidthAndPositionAndPreservesLandscapeAspect() {
        val placement = CardPlacement(-0.2f, 1.5f, 1.2f)
        val layout = IdCardLayout(front = placement, back = CardPlacement(0.5f, -1f, 0.1f))
        val aspect = IdCardComposer.cardAspect(100, 200)
        assertEquals(2f, aspect, 0f)
        assertEquals(aspect, IdCardComposer.cardAspect(200, 100), 0f)
        val (front, back) = IdCardComposer.cardRects(layout, aspect, 1f)
        assertEquals(CardRect(0, 2268, 2480, 1240), front)
        assertEquals(CardRect(1240, 0, 620, 620), back)
        assertEquals(front to back, front.placement().let {
            IdCardComposer.cardRects(layout.copy(front = it), aspect, 1f)
        })
    }

    @Test
    fun cardShapedSourcesUseThePhysicalCardRatio() {
        val aspect = IdCardComposer.cardAspect(638, 1011)
        assertEquals(85.6f / 53.98f, aspect, 0f)
    }

    @Test
    fun swapAndResetRespectPresetOrder() {
        val preset = IdCardLayout(outline = true)
        val (front, back) = IdCardComposer.cardRects(preset)
        assertEquals(back to front, IdCardComposer.cardRects(preset.swapCards()))
        val partial = preset.copy(front = back.placement())
        assertEquals(front to back, IdCardComposer.cardRects(partial))
        val free = partial.copy(back = front.placement())
        val swapped = free.swapCards()
        assertEquals(free.back, swapped.front)
        assertEquals(free.front, swapped.back)
        assertEquals(preset, swapped.resetPlacement())
        assertEquals(preset.swapCards(), free.copy(reversed = true).resetPlacement())
    }
}
