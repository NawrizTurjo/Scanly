package dev.turjo.paperless.feature.camera

import kotlin.math.abs

internal enum class AutoCaptureSensitivity(val changeThreshold: Float) {
    LOW(0.12f),
    NORMAL(0.08f),
    HIGH(0.05f),
}

internal enum class AutoCaptureState { SEARCHING, TRACKING, CAPTURING, COOLDOWN }
internal enum class AutoCaptureAction { CaptureNow }

internal class AutoCaptureController(
    private val delayMillis: Long = 700,
    sensitivity: AutoCaptureSensitivity = AutoCaptureSensitivity.NORMAL,
) {
    private val changeThreshold = sensitivity.changeThreshold
    private val sharpnessHistory = ArrayDeque<Float>()
    private var trackingSince = 0L
    private var lostSince: Long? = null
    private var lastCaptured: FloatArray? = null
    private var pendingCapture: FloatArray? = null
    var state = AutoCaptureState.SEARCHING
        private set
    var progress: Float? = null
        private set

    fun update(quad: OverlayQuad?, nowMillis: Long): AutoCaptureAction? {
        if (quad != null && quad.sharpness.isFinite() && quad.sharpness >= 0f) {
            if (sharpnessHistory.size == 30) sharpnessHistory.removeFirst()
            sharpnessHistory.addLast(quad.sharpness)
        }
        if (state == AutoCaptureState.CAPTURING) return null

        val fingerprint = quad?.validFingerprint()
        if (state == AutoCaptureState.COOLDOWN) {
            if (quad == null && lostSince == null) lostSince = nowMillis
            val lostLongEnough = lostSince?.let { nowMillis - it >= 400 } == true
            if (quad != null) lostSince = null
            val changed = fingerprint != null && lastCaptured != null && difference(fingerprint) > changeThreshold
            if (lostLongEnough || changed) state = AutoCaptureState.SEARCHING
            return null
        }

        if (quad == null) {
            stopTracking()
            return null
        }
        if (state == AutoCaptureState.SEARCHING) {
            state = AutoCaptureState.TRACKING
            trackingSince = nowMillis
        }
        val stableFor = minOf(quad.stableForMillis, nowMillis - trackingSince)
        progress = if (delayMillis <= 0) 1f else (stableFor.toFloat() / delayMillis).coerceIn(0f, 1f)
        if (stableFor < delayMillis || fingerprint == null) return null
        if (difference(fingerprint) <= changeThreshold) {
            enterCooldown()
            return null
        }
        if (!isSharp(quad.sharpness)) return null

        startCapture(quad)
        return AutoCaptureAction.CaptureNow
    }

    fun stopTracking() {
        if (state == AutoCaptureState.TRACKING) state = AutoCaptureState.SEARCHING
        progress = null
    }

    fun startCapture(quad: OverlayQuad?) {
        pendingCapture = quad?.validFingerprint()?.copyOf()
        state = AutoCaptureState.CAPTURING
        progress = null
    }

    fun captureFinished(succeeded: Boolean) {
        if (succeeded) lastCaptured = pendingCapture
        pendingCapture = null
        if (succeeded || lastCaptured != null) enterCooldown() else state = AutoCaptureState.SEARCHING
    }

    private fun enterCooldown() {
        state = AutoCaptureState.COOLDOWN
        lostSince = null
        progress = null
    }

    private fun difference(fingerprint: FloatArray): Float {
        val previous = lastCaptured ?: return Float.POSITIVE_INFINITY
        var total = 0f
        for (index in fingerprint.indices) total += abs(fingerprint[index] - previous[index])
        return total / fingerprint.size
    }

    private fun isSharp(sharpness: Float): Boolean {
        if (!sharpness.isFinite() || sharpness <= 0f) return false
        val sorted = sharpnessHistory.sorted()
        val median = (sorted[(sorted.size - 1) / 2] + sorted[sorted.size / 2]) / 2
        return sharpness >= 0.6f * median
    }

    private fun OverlayQuad.validFingerprint() =
        fingerprint.takeIf { it.size == 256 && it.all { luma -> luma in 0f..1f } }
}
