package dev.turjo.paperless

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.turjo.paperless.core.imaging.LiveQuadDetector
import dev.turjo.paperless.core.imaging.LumaFrame
import dev.turjo.paperless.core.imaging.QuadSmoother
import dev.turjo.paperless.feature.camera.LiveQuadSource
import dev.turjo.paperless.feature.camera.OverlayQuad

/** Binds the camera's optional live-overlay source to the OpenCV detector. Unscoped: one detector per camera screen. */
@Module
@InstallIn(SingletonComponent::class)
object LiveDetectionModule {
    @Provides
    fun liveQuadSource(): LiveQuadSource {
        val detector = LiveQuadDetector()
        val smoother = QuadSmoother()
        return LiveQuadSource { frame ->
            val quad = detector.detect(LumaFrame(frame.y, frame.width, frame.height, frame.rowStride, frame.rotationDegrees))
            smoother.update(quad, frame.timestampNanos)?.let {
                OverlayQuad(it.points, it.confidence, it.stableForMillis, it.sharpness, it.fingerprint)
            }
        }
    }
}
