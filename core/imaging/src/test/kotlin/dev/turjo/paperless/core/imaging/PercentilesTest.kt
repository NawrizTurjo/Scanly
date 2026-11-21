package dev.turjo.paperless.core.imaging

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PercentilesTest {
    @Test
    fun histogramInterpolatesRanksAndTreatsBytesAsUnsigned() {
        val bins = Percentiles.histogram(byteArrayOf(255.toByte(), 10, 0, 10, 200.toByte()))
        assertEquals(0.0, Percentiles.ofHistogram(bins, 0.0), 0.0)
        assertEquals(0.2, Percentiles.ofHistogram(bins, 0.5), 1e-12)
        assertEquals(10.0, Percentiles.ofHistogram(bins, 50.0), 0.0)
        assertEquals(86.0, Percentiles.ofHistogram(bins, 60.0), 1e-12)
        assertEquals(255.0, Percentiles.ofHistogram(bins, 100.0), 0.0)
        assertEquals(200.0, Percentiles.ofHistogram(Percentiles.histogram(byteArrayOf(200.toByte())), 85.0), 0.0)
    }

    @Test
    fun floatPercentileSortsACopyAndInterpolates() {
        val samples = floatArrayOf(16f, -2f, 5f, 1f)
        assertEquals(11.05, Percentiles.ofFloats(samples, 85.0), 1e-12)
        assertEquals(3.0, Percentiles.ofFloats(samples, 50.0), 0.0)
        assertArrayEquals(floatArrayOf(16f, -2f, 5f, 1f), samples, 0f)
    }
}

