package dev.turjo.paperless.feature.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveQuadMetricsTest {
    @Test fun equalRatiosMapDirectlyToViewfinderPixels() {
        assertPoint(160f, 360f, mapToViewfinder(0.25f, 0.75f, 640f, 480f, 4f / 3))
    }

    @Test fun widerViewfinderCropsTopAndBottomInsteadOfLetterboxing() {
        assertPoint(0f, -60f, mapToViewfinder(0f, 0f, 800f, 480f, 4f / 3))
        assertPoint(800f, 540f, mapToViewfinder(1f, 1f, 800f, 480f, 4f / 3))
    }

    @Test fun tallerViewfinderCropsSidesInsteadOfPillarboxing() {
        assertPoint(-160f, 0f, mapToViewfinder(0f, 0f, 640f, 720f, 4f / 3))
        assertPoint(800f, 720f, mapToViewfinder(1f, 1f, 640f, 720f, 4f / 3))
    }

    @Test fun uprightPointsMapIdenticallyRegardlessOfSourceRotation() {
        for ((width, height, rotation) in listOf(
            Triple(480, 640, 0), Triple(640, 480, 90),
            Triple(480, 640, 180), Triple(640, 480, 270),
        )) {
            val aspect = uprightAspectRatio(width, height, rotation)
            assertPoint(120f, 480f, mapToViewfinder(0.25f, 0.75f, 480f, 640f, aspect))
        }
    }

    @Test fun skipsFramesUntilPreviousResultIsConsumed() {
        val gate = AnalysisFrameGate()
        assertTrue(gate.tryStart())
        assertFalse(gate.tryStart())
        assertFalse(gate.tryStart())
        gate.acknowledge()
        assertTrue(gate.tryStart())
        assertFalse(gate.tryStart())
    }

    @Test fun fpsCountsProcessedFramesInTheLastSecondAndDecaysWithoutInput() {
        val fps = AnalysisFpsCounter()
        assertEquals(0, fps.fps(0))
        fps.record(0)
        fps.record(400_000_000)
        fps.record(700_000_000)
        assertEquals(3, fps.fps(999_999_999))
        assertEquals(2, fps.fps(1_000_000_000))
        assertEquals(1, fps.fps(1_400_000_000))
        assertEquals(0, fps.fps(1_700_000_000))
    }

    @Test fun stabilityColorRampClampsAndReachesGreenAt700Millis() {
        assertEquals(0f, stabilityColorFraction(-1), 0f)
        assertEquals(0f, stabilityColorFraction(0), 0f)
        assertEquals(0.5f, stabilityColorFraction(350), 0f)
        assertEquals(1f, stabilityColorFraction(700), 0f)
        assertEquals(1f, stabilityColorFraction(900), 0f)
    }

    private fun assertPoint(x: Float, y: Float, point: ViewfinderPoint) {
        assertEquals(x, point.x, 0.001f)
        assertEquals(y, point.y, 0.001f)
    }
}
