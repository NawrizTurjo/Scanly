package dev.turjo.paperless.feature.camera

import dagger.BindsOptionalOf
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.nio.ByteBuffer

/** The Y plane is valid only during [LiveQuadSource.process]. */
class AnalysisFrame(
    val y: ByteBuffer,
    val width: Int,
    val height: Int,
    val rowStride: Int,
    val rotationDegrees: Int,
    val timestampNanos: Long,
)

/** Points are normalized upright-frame coordinates in TL, TR, BR, BL order. */
data class OverlayQuad(val points: FloatArray, val confidence: Float, val stableForMillis: Long)

fun interface LiveQuadSource {
    fun process(frame: AnalysisFrame): OverlayQuad?
}

@Module
@InstallIn(SingletonComponent::class)
interface LiveQuadModule {
    @BindsOptionalOf
    fun liveQuadSource(): LiveQuadSource
}
