package dev.turjo.paperless.feature.editor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext

/** A rectangle in full-image pixels. */
internal data class TileRegion(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top
}

/** A decoded piece of the full page: [region] in full-image pixels. */
internal class ZoomTile(val image: ImageBitmap, val region: TileRegion)

/** Where the page sits on screen: the base image is fitted to the viewport, then scaled about its centre and moved. */
internal data class PagePlacement(val left: Float, val top: Float, val pixelScale: Float)

internal fun pagePlacement(viewport: Size, image: IntSize, scale: Float, translation: Offset): PagePlacement {
    val fit = minOf(viewport.width / image.width, viewport.height / image.height)
    val pixelScale = fit * scale
    return PagePlacement(
        left = viewport.width / 2 + translation.x - image.width * pixelScale / 2,
        top = viewport.height / 2 + translation.y - image.height * pixelScale / 2,
        pixelScale = pixelScale,
    )
}

/**
 * The part of the page on screen, in full-image pixels, and the power-of-two subsampling that still
 * gives at least one image pixel per screen pixel. Null when nothing is visible.
 */
internal fun visibleRegion(viewport: Size, image: IntSize, placement: PagePlacement): Pair<TileRegion, Int>? {
    val s = placement.pixelScale
    val left = ((0f - placement.left) / s).coerceAtLeast(0f)
    val top = ((0f - placement.top) / s).coerceAtLeast(0f)
    val right = ((viewport.width - placement.left) / s).coerceAtMost(image.width.toFloat())
    val bottom = ((viewport.height - placement.top) / s).coerceAtMost(image.height.toFloat())
    if (right <= left || bottom <= top) return null
    var sample = 1
    while (sample * 2 <= 1f / s) sample *= 2
    return TileRegion(left.toInt(), top.toInt(), right.roundToInt(), bottom.roundToInt()) to sample
}

/**
 * Sharp detail while zoomed: decodes only the visible part of [file] at the zoom's resolution, once
 * the gesture pauses, and draws it over the screen-sized base image. The tile follows the live
 * transform, so it stays aligned while the user keeps panning.
 */
@Composable
internal fun ZoomTileLayer(file: File?, active: Boolean, scale: () -> Float, translation: () -> Offset) {
    val imageSize by produceState<IntSize?>(null, file) {
        value = file?.let {
            withContext(Dispatchers.IO) {
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(it.path, options)
                IntSize(options.outWidth, options.outHeight).takeIf { size -> size.width > 0 && size.height > 0 }
            }
        }
    }
    val decoder = remember(file) { lazy { file?.let { runCatching { BitmapRegionDecoder.newInstance(it.path) }.getOrNull() } } }
    DisposableEffect(decoder) {
        onDispose { if (decoder.isInitialized()) decoder.value?.recycle() }
    }
    var viewport by remember { mutableStateOf(Size.Zero) }
    var tile by remember(file) { mutableStateOf<ZoomTile?>(null) }
    val fullSize = imageSize
    LaunchedEffect(file, fullSize, viewport, active) {
        if (fullSize == null || viewport == Size.Zero || !active) {
            tile = null
            return@LaunchedEffect
        }
        snapshotFlow { scale() to translation() }.collectLatest { (zoom, offset) ->
            if (zoom <= 1.05f) {
                tile = null
                return@collectLatest
            }
            delay(120) // Wait for the gesture to pause; a newer position cancels this one.
            val (region, sample) = visibleRegion(viewport, fullSize, pagePlacement(viewport, fullSize, zoom, offset))
                ?: return@collectLatest
            val bitmap = withContext(Dispatchers.IO) {
                runCatching {
                    decoder.value?.decodeRegion(
                        Rect(region.left, region.top, region.right, region.bottom),
                        BitmapFactory.Options().apply {
                            inSampleSize = sample
                            inPreferredConfig = Bitmap.Config.ARGB_8888
                        },
                    )
                }.getOrNull()
            } ?: return@collectLatest
            tile = ZoomTile(bitmap.asImageBitmap(), region)
        }
    }
    Canvas(Modifier.fillMaxSize().onSizeChanged { viewport = Size(it.width.toFloat(), it.height.toFloat()) }) {
        val current = tile ?: return@Canvas
        val full = fullSize ?: return@Canvas
        val placement = pagePlacement(this.size, full, scale(), translation())
        val s = placement.pixelScale
        val region = current.region
        drawImage(
            current.image,
            dstOffset = IntOffset(
                (placement.left + region.left * s).roundToInt(),
                (placement.top + region.top * s).roundToInt(),
            ),
            dstSize = IntSize((region.width * s).roundToInt(), (region.height * s).roundToInt()),
            filterQuality = FilterQuality.High,
        )
    }
}
