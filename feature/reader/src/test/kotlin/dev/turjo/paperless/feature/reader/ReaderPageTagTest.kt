package dev.turjo.paperless.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderPageTagTest {
    @Test
    fun formatsSinglePageAndRange() {
        assertEquals("4 / 7", pageTagLabel(4, 4, 7))
        assertEquals("4–5 / 7", pageTagLabel(4, 5, 7))
    }
}
