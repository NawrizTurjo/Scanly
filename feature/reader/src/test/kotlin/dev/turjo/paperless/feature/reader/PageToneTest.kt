package dev.turjo.paperless.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PageToneTest {
    @Test
    fun normalUsesTheOriginalBitmap() {
        assertNull(pageToneMatrix(PageTone.NORMAL))
    }

    @Test
    fun whitePaperMapsToTheExpectedColors() {
        val expected = mapOf(
            PageTone.NORMAL to intArrayOf(255, 255, 255),
            PageTone.NIGHT to intArrayOf(18, 18, 18),
            PageTone.SEPIA to intArrayOf(244, 236, 216),
            PageTone.WARM to intArrayOf(255, 241, 196),
        )
        for ((tone, rgb) in expected) {
            val paper = transform(tone, 255f, 255f, 255f)
            for (channel in rgb.indices) {
                assertEquals("$tone channel $channel", rgb[channel].toFloat(), paper[channel], 6f)
            }
        }
    }

    @Test
    fun blackTextStaysDarkExceptInNightTone() {
        for (tone in PageTone.entries) {
            val (red, green, blue) = transform(tone, 0f, 0f, 0f)
            val luminance = (0.2126f * red + 0.7152f * green + 0.0722f * blue) / 255f
            assertTrue("$tone luminance was $luminance", if (tone == PageTone.NIGHT) {
                luminance > 0.7f
            } else {
                luminance < 0.2f
            })
        }
    }

    private fun transform(tone: PageTone, red: Float, green: Float, blue: Float): FloatArray {
        val matrix = pageToneMatrix(tone) ?: return floatArrayOf(red, green, blue)
        return FloatArray(3) { channel ->
            val row = channel * 5
            (matrix[row] * red + matrix[row + 1] * green + matrix[row + 2] * blue +
                matrix[row + 3] * 255f + matrix[row + 4]).coerceIn(0f, 255f)
        }
    }
}
