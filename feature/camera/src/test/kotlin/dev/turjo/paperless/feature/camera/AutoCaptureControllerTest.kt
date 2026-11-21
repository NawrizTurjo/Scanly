package dev.turjo.paperless.feature.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutoCaptureControllerTest {
    private val controller = AutoCaptureController()

    @Test fun stablePageTriggersOnce() {
        assertNull(controller.update(frame(), 0))
        assertNull(controller.update(frame(stable = 699), 699))
        assertEquals(AutoCaptureAction.CaptureNow, controller.update(frame(stable = 700), 700))
        assertNull(controller.update(frame(stable = 900), 900))
        assertEquals(AutoCaptureState.CAPTURING, controller.state)
    }

    @Test fun jitterDoesNotCapture() {
        repeat(20) { assertNull(controller.update(frame(stable = 50), it * 100L)) }
        assertEquals(AutoCaptureState.TRACKING, controller.state)
    }

    @Test fun samePageRemainsInCooldownEvenAfterQuadLossAndReturn() {
        captureFirstPage()
        assertNull(controller.update(frame(stable = 1500), 1500))
        controller.update(null, 1600)
        controller.update(null, 1999)
        assertEquals(AutoCaptureState.COOLDOWN, controller.state)
        controller.update(null, 2000)
        assertEquals(AutoCaptureState.SEARCHING, controller.state)
        controller.update(frame(), 2100)
        assertNull(controller.update(frame(stable = 700), 2800))
        assertEquals(AutoCaptureState.COOLDOWN, controller.state)
    }

    @Test fun changedPageRestartsTrackingThenCaptures() {
        captureFirstPage()
        assertNull(controller.update(frame(luma = 0.8f), 800))
        assertEquals(AutoCaptureState.SEARCHING, controller.state)
        controller.update(frame(luma = 0.8f), 900)
        assertEquals(AutoCaptureAction.CaptureNow, controller.update(frame(700, 0.8f), 1600))
    }

    @Test fun lostQuadClearsTrackingProgress() {
        controller.update(frame(), 0)
        controller.update(frame(350), 350)
        assertEquals(0.5f, controller.progress!!, 0f)
        controller.update(null, 400)
        assertEquals(AutoCaptureState.SEARCHING, controller.state)
        assertNull(controller.progress)
        assertNull(controller.update(frame(700), 700))
    }

    @Test fun blurryFrameWaitsForSharpFrame() {
        repeat(30) { controller.update(frame(), it * 20L) }
        assertNull(controller.update(frame(700, sharpness = 5f), 700))
        assertEquals(AutoCaptureState.TRACKING, controller.state)
        assertEquals(AutoCaptureAction.CaptureNow, controller.update(frame(750, sharpness = 6f), 750))
    }

    @Test fun manualCaptureSetsDuplicateGuard() {
        controller.startCapture(frame())
        controller.captureFinished(true)
        assertNull(controller.update(frame(700), 700))
        assertEquals(AutoCaptureState.COOLDOWN, controller.state)
    }

    @Test fun defaultsWithoutMetricsDoNotTriggerCapture() {
        controller.update(OverlayQuad(FloatArray(8), 1f, 0), 0)
        assertNull(controller.update(OverlayQuad(FloatArray(8), 1f, 700), 700))
    }

    @Test fun sensitivityChangesCooldownThreshold() {
        for (sensitivity in AutoCaptureSensitivity.entries) {
            val capture = AutoCaptureController(sensitivity = sensitivity)
            capture.startCapture(frame(luma = 0.2f))
            capture.captureFinished(true)
            capture.update(frame(luma = 0.27f), 800)
            val expected = if (sensitivity == AutoCaptureSensitivity.HIGH) {
                AutoCaptureState.SEARCHING
            } else {
                AutoCaptureState.COOLDOWN
            }
            assertEquals(expected, capture.state)
        }
    }

    private fun captureFirstPage() {
        controller.update(frame(), 0)
        assertEquals(AutoCaptureAction.CaptureNow, controller.update(frame(700), 700))
        controller.captureFinished(true)
    }

    private fun frame(stable: Long = 0, luma: Float = 0.2f, sharpness: Float = 10f) =
        OverlayQuad(FloatArray(8), 1f, stable, sharpness, FloatArray(256) { luma })
}
