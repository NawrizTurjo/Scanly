package dev.turjo.paperless.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderPageTagTest {
    @Test
    fun formatsSinglePageAndRange() {
        assertEquals("4 / 7", pageTagLabel(4, 4, 7))
        assertEquals("4–5 / 7", pageTagLabel(4, 5, 7))
    }

    @Test
    fun dragPositionMapsToPagesAndBack() {
        assertEquals(0f, pageTagFraction(1, 300), 1e-6f)
        assertEquals(1f, pageTagFraction(300, 300), 1e-6f)
        assertEquals(0, pageForFraction(0f, 300))
        assertEquals(299, pageForFraction(1f, 300))
        assertEquals(150, pageForFraction(0.5f, 301))
        assertEquals(0, pageForFraction(-0.2f, 10))
        assertEquals(9, pageForFraction(1.4f, 10))
        for (page in 1..37) assertEquals(page - 1, pageForFraction(pageTagFraction(page, 37), 37))
    }
}
