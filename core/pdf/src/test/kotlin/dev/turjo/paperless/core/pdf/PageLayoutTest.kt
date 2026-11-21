package dev.turjo.paperless.core.pdf

import org.junit.Assert.assertEquals
import org.junit.Test

class PageLayoutTest {
    @Test
    fun autoMatchesImageAspectRatio() {
        assertLayout(PageSizeOption.AUTO, 300, 600, 421f, 842f, 0f, 0f, 421f, 842f)
        assertLayout(PageSizeOption.AUTO, 600, 300, 842f, 421f, 0f, 0f, 842f, 421f)
        assertLayout(PageSizeOption.AUTO, 600, 600, 842f, 842f, 0f, 0f, 842f, 842f)
    }

    @Test
    fun a4FollowsImageOrientationAndCentersImage() {
        assertLayout(PageSizeOption.A4, 300, 600, 595f, 842f, 87f, 0f, 421f, 842f)
        assertLayout(PageSizeOption.A4, 600, 300, 842f, 595f, 0f, 87f, 842f, 421f)
        assertLayout(PageSizeOption.A4, 600, 600, 595f, 842f, 0f, 123.5f, 595f, 595f)
    }

    @Test
    fun letterFollowsImageOrientationAndCentersImage() {
        assertLayout(PageSizeOption.LETTER, 300, 600, 612f, 792f, 108f, 0f, 396f, 792f)
        assertLayout(PageSizeOption.LETTER, 600, 300, 792f, 612f, 0f, 108f, 792f, 396f)
        assertLayout(PageSizeOption.LETTER, 600, 600, 612f, 792f, 0f, 90f, 612f, 612f)
    }

    private fun assertLayout(
        option: PageSizeOption,
        imageWidth: Int,
        imageHeight: Int,
        pageWidth: Float,
        pageHeight: Float,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
    ) {
        val box = PageLayout.pageBox(option, imageWidth, imageHeight)
        val placement = PageLayout.placement(option, imageWidth, imageHeight)
        assertEquals(pageWidth, box.width, 0f)
        assertEquals(pageHeight, box.height, 0f)
        assertEquals(x, placement.x, 0f)
        assertEquals(y, placement.y, 0f)
        assertEquals(width, placement.w, 0f)
        assertEquals(height, placement.h, 0f)
    }
}
