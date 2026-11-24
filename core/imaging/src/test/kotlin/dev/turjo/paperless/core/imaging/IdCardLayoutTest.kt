package dev.turjo.paperless.core.imaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IdCardLayoutTest {
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
}
