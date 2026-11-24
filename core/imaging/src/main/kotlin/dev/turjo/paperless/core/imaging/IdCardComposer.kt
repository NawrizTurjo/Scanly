package dev.turjo.paperless.core.imaging

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.media.ExifInterface
import java.io.File
import kotlin.math.roundToInt

data class IdCardLayout(
    val arrangement: Arrangement = Arrangement.STACKED,
    val size: Size = Size.ACTUAL,
    val gapMm: Int = 6,
    val outline: Boolean = false,
) {
    enum class Arrangement { STACKED, SIDE_BY_SIDE }
    enum class Size { ACTUAL, LARGE }

    init {
        require(gapMm in setOf(0, 6, 15))
    }
}

internal data class CardRect(val left: Int, val top: Int, val width: Int, val height: Int)

object IdCardComposer {
    const val PAGE_WIDTH = 2480
    const val PAGE_HEIGHT = 3508
    private const val CARD_WIDTH = 1011
    private const val CARD_HEIGHT = 638
    private const val MARGIN = 118

    /** Blocking. Front and back are already processed and upright. */
    fun compose(front: File, back: File, output: File, layout: IdCardLayout = IdCardLayout()) {
        val page = Bitmap.createBitmap(PAGE_WIDTH, PAGE_HEIGHT, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(page)
            canvas.drawColor(Color.WHITE)
            val (frontRect, backRect) = cardRects(layout)
            drawCard(canvas, front, frontRect, layout.outline)
            drawCard(canvas, back, backRect, layout.outline)
            output.outputStream().use { check(page.compress(Bitmap.CompressFormat.JPEG, 92, it)) }
            ExifInterface(output.path).apply {
                setAttribute(ExifInterface.TAG_X_RESOLUTION, "300/1")
                setAttribute(ExifInterface.TAG_Y_RESOLUTION, "300/1")
                setAttribute(ExifInterface.TAG_RESOLUTION_UNIT, "2")
                saveAttributes()
            }
        } finally {
            page.recycle()
        }
    }

    internal fun cardRects(layout: IdCardLayout): Pair<CardRect, CardRect> {
        val gap = (layout.gapMm * 300 / 25.4).roundToInt()
        val stacked = layout.arrangement == IdCardLayout.Arrangement.STACKED
        val pairWidth = if (layout.size == IdCardLayout.Size.LARGE) PAGE_WIDTH * 4 / 5 else PAGE_WIDTH - 2 * MARGIN
        val fitWidth = if (stacked) pairWidth else (pairWidth - gap) / 2
        val width = if (layout.size == IdCardLayout.Size.LARGE) fitWidth else minOf(CARD_WIDTH, fitWidth)
        val height = (width * CARD_HEIGHT.toDouble() / CARD_WIDTH).roundToInt()
        val first = CardRect(
            left = (PAGE_WIDTH - if (stacked) width else 2 * width + gap) / 2,
            top = MARGIN,
            width = width,
            height = height,
        )
        val second = if (stacked) first.copy(top = first.top + height + gap)
        else first.copy(left = first.left + width + gap)
        return first to second
    }

    private fun drawCard(canvas: Canvas, source: File, bounds: CardRect, outline: Boolean) {
        val card = requireNotNull(BitmapFactory.decodeFile(source.path)) { "Cannot decode ${source.name}" }
        try {
            val destination = Rect(bounds.left, bounds.top, bounds.left + bounds.width, bounds.top + bounds.height)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            if (card.height > card.width) {
                canvas.save()
                canvas.rotate(90f, destination.exactCenterX(), destination.exactCenterY())
                val rotatedLeft = destination.exactCenterX() - bounds.height / 2f
                val rotatedTop = destination.exactCenterY() - bounds.width / 2f
                canvas.drawBitmap(card, null, RectF(
                    rotatedLeft, rotatedTop, rotatedLeft + bounds.height, rotatedTop + bounds.width,
                ), paint)
                canvas.restore()
            } else {
                canvas.drawBitmap(card, null, destination, paint)
            }
            if (outline) {
                paint.color = Color.rgb(221, 221, 221)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 1f
                canvas.drawRect(
                    destination.left + 0.5f, destination.top + 0.5f,
                    destination.right - 0.5f, destination.bottom - 0.5f, paint,
                )
            }
        } finally {
            card.recycle()
        }
    }
}
