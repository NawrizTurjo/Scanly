package dev.turjo.paperless.core.imaging

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QuadSmootherTest {
    @Test
    fun takesCoordinateMedianOverRollingWindow() {
        val smoother = QuadSmoother(window = 3)
        smoother.update(quad(), 0)
        assertPoints(quad(0.02f), smoother.update(quad(0.04f), ms(10)))
        assertPoints(quad(0.02f), smoother.update(quad(0.02f), ms(20)))
        assertPoints(quad(0.04f), smoother.update(quad(0.06f), ms(30)))
    }

    @Test
    fun alignsCyclicCornersBeforeMeasuringMovement() {
        val smoother = QuadSmoother()
        val input = quad()
        smoother.update(input, 0)
        val rotated = LiveQuad(FloatArray(8) { input.points[(it + 6) % 8] }, 0.7f)
        val result = requireNotNull(smoother.update(rotated, ms(50)))
        assertPoints(input, result)
        assertEquals(0f, result.movement, 0f)
        assertEquals(0.7f, result.confidence, 0f)
    }

    @Test
    fun jumpClearsHistory() {
        val smoother = QuadSmoother()
        smoother.update(quad(), 0)
        smoother.update(quad(0.01f), ms(10))
        val jumped = requireNotNull(smoother.update(quad(0.14f), ms(20)))
        assertPoints(quad(0.14f), jumped)
        assertEquals(0.13f, jumped.movement, 0.00001f)
        assertEquals(0L, jumped.stableForMillis)
        assertPoints(quad(0.145f), smoother.update(quad(0.15f), ms(30)))
    }

    @Test
    fun holdsForTwoMissesAndRestartsStabilityAfterAnInterruption() {
        val smoother = QuadSmoother()
        assertNull(smoother.update(null, 0))
        smoother.update(quad(), ms(10))
        smoother.update(quad(), ms(20))
        val held = requireNotNull(smoother.update(null, ms(30)))
        assertPoints(quad(), held)
        assertEquals(0L, held.stableForMillis)
        assertPoints(quad(), smoother.update(null, ms(40)))
        assertEquals(0L, requireNotNull(smoother.update(quad(), ms(50))).stableForMillis)
        smoother.update(null, ms(60))
        smoother.update(null, ms(70))
        assertNull(smoother.update(null, ms(80)))
        assertPoints(quad(0.02f), smoother.update(quad(0.02f), ms(90)))
        smoother.reset()
        assertNull(smoother.update(null, ms(100)))
    }

    @Test
    fun tracksStabilityAndMovementBetweenRawFrames() {
        val smoother = QuadSmoother()
        smoother.update(quad(), ms(100))
        val steady = requireNotNull(smoother.update(quad(0.01f), ms(160)))
        assertEquals(60L, steady.stableForMillis)
        assertEquals(0.01f, steady.movement, 0.00001f)
        val moved = requireNotNull(smoother.update(quad(0.04f), ms(220)))
        assertEquals(0L, moved.stableForMillis)
        assertEquals(0.03f, moved.movement, 0.00001f)
        assertEquals(80L, requireNotNull(smoother.update(quad(0.05f), ms(300))).stableForMillis)
    }

    private fun quad(dx: Float = 0f): LiveQuad = LiveQuad(
        floatArrayOf(0.1f + dx, 0.2f, 0.6f + dx, 0.2f, 0.6f + dx, 0.7f, 0.1f + dx, 0.7f),
        0.9f,
    )

    private fun assertPoints(expected: LiveQuad, actual: SmoothedQuad?) {
        assertArrayEquals(expected.points, requireNotNull(actual).points, 0.00001f)
    }

    private fun ms(value: Long): Long = value * 1_000_000
}
