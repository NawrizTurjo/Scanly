package dev.turjo.paperless.feature.reader

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntRect
import dev.turjo.paperless.core.model.PageRect
import dev.turjo.paperless.core.model.scaleFromUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SignaturePlacementTest {
    @Test
    fun trimFindsNonzeroAlphaAndClampsTheMargin() {
        val pixels = IntArray(8 * 7)
        assertNull(inkBounds(pixels, 8, 7, margin = 1))
        pixels[2 * 8] = 0x01000000
        pixels[3 * 8 + 4] = 0xFF1648AE.toInt()
        assertEquals(IntRect(0, 1, 6, 5), inkBounds(pixels, 8, 7, margin = 1))
    }

    @Test
    fun placementAndResizePreserveInkAspectOnPortraitAndRotatedPages() {
        for ((width, height) in listOf(400f to 800f, 800f to 400f)) {
            val transform = PageTransform(PageRect(20f, 30f, width - 10, height - 20), 700f, 700f)
            val full = PageRect(0f, 0f, width, height)
            val ratio = 3f * height / width
            for (requestedWidth in listOf(0.28f, 0.56f, 1.5f)) {
                val normalized = fitSignature(Offset(0.99f, 0.8f), requestedWidth, ratio)
                val points = normalized.scaleFromUnit(full)
                assertEquals(3f, (points.right - points.left) / (points.bottom - points.top), 0.001f)
                assertEquals(1f, normalized.right, 0.001f)
                val view = transform.map(points)
                val (x, y) = transform.unmap(view.left, view.top)
                assertEquals(normalized.left, x / width, 0.001f)
                assertEquals(normalized.top, y / height, 0.001f)
            }
        }
    }
}
