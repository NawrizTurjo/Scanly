package dev.turjo.paperless.feature.tools

import org.junit.Assert.assertEquals
import org.junit.Test

class ImageEnhancementTest {
    @Test fun defaultsToDocument() {
        assertEquals(ImageEnhancement.DOCUMENT, emptyMap<String, String>().imageEnhancement())
    }

    @Test fun choicesMapToPageProcessorFilters() {
        val filters = mapOf(
            "DOCUMENT" to "DOCUMENT",
            "COLOUR" to "AUTO",
            "BLACK_AND_WHITE" to "BW",
            "GRAYSCALE" to "GRAY",
            "OFF" to null,
        )
        assertEquals(filters.keys, ImageEnhancement.entries.map { it.name }.toSet())
        for ((choice, filter) in filters) {
            assertEquals(choice, filter, mapOf(ENHANCE_OPTION to choice).imageEnhancement().filter)
        }
    }
}
