package dev.turjo.paperless.core.imaging

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class GeometryTest {
    @Test
    fun ordersUnsortedCornersInImageAxes() {
        val scrambled = floatArrayOf(9f, 8f, 1f, 7f, 8f, 2f, 2f, 1f)
        assertArrayEquals(floatArrayOf(2f, 1f, 8f, 2f, 9f, 8f, 1f, 7f),
            Geometry.orderQuad(scrambled), 0f)
    }

    @Test
    fun snapsNearPaperRatiosWithoutChangingTheLongSide() {
        assertEquals(210 to 297, Geometry.dimensions(rectangle(213f, 297f), snap = true))
        assertEquals(1100 to 850, Geometry.dimensions(rectangle(1100f, 856f), snap = true))
        assertEquals(213 to 297, Geometry.dimensions(rectangle(213f, 297f), snap = false))
        assertEquals(200 to 200, Geometry.dimensions(rectangle(200f, 200f), snap = true))
        assertEquals(1 to 1, Geometry.dimensions(FloatArray(8), snap = true))
    }

    private fun rectangle(w: Float, h: Float) = floatArrayOf(0f, 0f, w, 0f, w, h, 0f, h)
}

