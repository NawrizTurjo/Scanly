package dev.turjo.paperless.feature.camera

import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

internal data class ViewfinderPoint(val x: Float, val y: Float)

internal fun mapToViewfinder(
    x: Float,
    y: Float,
    viewWidth: Float,
    viewHeight: Float,
    uprightAspectRatio: Float,
): ViewfinderPoint {
    val scaledHeight = max(viewHeight, viewWidth / uprightAspectRatio)
    val scaledWidth = scaledHeight * uprightAspectRatio
    return ViewfinderPoint(
        viewWidth / 2 + (x - 0.5f) * scaledWidth,
        viewHeight / 2 + (y - 0.5f) * scaledHeight,
    )
}

internal fun uprightAspectRatio(width: Int, height: Int, rotationDegrees: Int): Float =
    if (rotationDegrees % 180 == 0) width.toFloat() / height else height.toFloat() / width

internal fun stabilityColorFraction(stableForMillis: Long): Float =
    stableForMillis.coerceIn(0, 700) / 700f

internal class AnalysisFrameGate {
    private val pending = AtomicBoolean()

    fun tryStart(): Boolean = pending.compareAndSet(false, true)

    fun acknowledge() {
        pending.set(false)
    }
}

internal class AnalysisFpsCounter {
    private val frames = ArrayDeque<Long>()

    @Synchronized
    fun record(nowNanos: Long) {
        discardOldFrames(nowNanos)
        frames.addLast(nowNanos)
    }

    @Synchronized
    fun fps(nowNanos: Long): Int {
        discardOldFrames(nowNanos)
        return frames.size
    }

    private fun discardOldFrames(nowNanos: Long) {
        val cutoff = nowNanos - 1_000_000_000L
        while (frames.isNotEmpty() && frames.first() <= cutoff) frames.removeFirst()
    }
}
