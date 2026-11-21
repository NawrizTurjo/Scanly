package dev.turjo.paperless.core.imaging

import kotlin.math.hypot

data class SmoothedQuad(
    val points: FloatArray,
    val confidence: Float,
    val stableForMillis: Long,
    val movement: Float,
    val sharpness: Float = 0f,
    val fingerprint: FloatArray = FloatArray(0),
)

/** Smooths the overlay and measures how long consecutive detections have stayed steady. */
class QuadSmoother(private val window: Int = 4, private val jumpThreshold: Float = 0.08f) {
    private val history = ArrayDeque<FloatArray>()
    private var smoothed: SmoothedQuad? = null
    private var stableSinceNanos = 0L
    private var misses = 0

    init {
        require(window > 0 && jumpThreshold >= 0f)
    }

    fun update(quad: LiveQuad?, timestampNanos: Long): SmoothedQuad? {
        if (quad == null) {
            val last = smoothed ?: return null
            if (++misses == 3) {
                reset()
                return null
            }
            return last.copy(points = last.points.copyOf(), stableForMillis = 0, fingerprint = last.fingerprint.copyOf())
        }

        require(quad.points.size == 8)
        val previous = history.lastOrNull()
        val points = if (previous == null) quad.points.copyOf() else align(quad.points, previous)
        val movement = previous?.let { distance(points, it) } ?: 0f
        val current = smoothed
        val jumped = current != null && distance(points, current.points) > jumpThreshold
        if (jumped) history.clear()
        if (previous == null || jumped || misses > 0 || movement >= 0.015f) {
            stableSinceNanos = timestampNanos
        }
        misses = 0

        if (history.size == window) history.removeFirst()
        history.addLast(points)
        val samples = FloatArray(history.size)
        val median = FloatArray(8) { coordinate ->
            history.forEachIndexed { index, sample -> samples[index] = sample[coordinate] }
            samples.sort()
            (samples[(samples.size - 1) / 2] + samples[samples.size / 2]) * 0.5f
        }
        val elapsedMillis = (timestampNanos - stableSinceNanos).coerceAtLeast(0) / 1_000_000
        val result = SmoothedQuad(median, quad.confidence, elapsedMillis, movement, quad.sharpness, quad.fingerprint.copyOf())
        smoothed = result.copy(points = median.copyOf(), fingerprint = result.fingerprint.copyOf())
        return result
    }

    fun reset() {
        history.clear()
        smoothed = null
        stableSinceNanos = 0
        misses = 0
    }

    private fun align(points: FloatArray, previous: FloatArray): FloatArray {
        val offset = (0..3).minBy { distance(points, previous, it * 2) } * 2
        return FloatArray(8) { points[(it + offset) % 8] }
    }

    private fun distance(points: FloatArray, reference: FloatArray, offset: Int = 0): Float {
        var sum = 0f
        for (i in 0 until 8 step 2) {
            val start = (i + offset) % 8
            sum += hypot(points[start] - reference[i], points[start + 1] - reference[i + 1])
        }
        return sum / 4
    }
}
