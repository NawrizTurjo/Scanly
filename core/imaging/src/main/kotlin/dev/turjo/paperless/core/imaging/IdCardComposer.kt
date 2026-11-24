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
    val front: CardPlacement? = null,
    val back: CardPlacement? = null,
    val reversed: Boolean = false,
    val extraPlacements: List<CardPlacement?> = emptyList(),
    val order: List<Int> = emptyList(),
) {
    enum class Arrangement { STACKED, SIDE_BY_SIDE }
    enum class Size { ACTUAL, LARGE }

    val isFree: Boolean get() = front != null && back != null || extraPlacements.isNotEmpty()

    fun resetPlacement() = copy(front = null, back = null, extraPlacements = emptyList())

    fun swapCards() = if (isFree) copy(front = back, back = front)
    else copy(reversed = !reversed)

    fun sourceOrder(count: Int): List<Int> =
        order.takeIf { it.size == count && it.sorted() == (0 until count).toList() }
            ?: (0 until count).toList()

    fun placements(count: Int): List<CardPlacement?> =
        List(count) { (listOf(front, back) + extraPlacements).getOrNull(it) }

    fun withPlacements(placements: List<CardPlacement?>) = copy(
        front = placements.getOrNull(0),
        back = placements.getOrNull(1),
        extraPlacements = placements.drop(2),
    )

    fun moveCard(source: Int, target: Int, count: Int): IdCardLayout {
        val order = sourceOrder(count).toMutableList()
        val position = order.indexOf(source)
        val destination = order.indexOf(target)
        order[position] = target
        order[destination] = source
        val next = copy(order = order)
        if (!isFree) return next
        val placements = placements(count).toMutableList()
        val original = placements[source]
        placements[source] = placements[target]
        placements[target] = original
        return next.withPlacements(placements)
    }

    internal fun placementFor(source: Int): CardPlacement? = when (source) {
        0 -> front
        1 -> back
        else -> extraPlacements.getOrNull(source - 2)
    }

    init {
        require(gapMm in setOf(0, 6, 15))
    }
}

data class CardPlacement(val left: Float, val top: Float, val width: Float) {
    init {
        require(left.isFinite() && top.isFinite() && width.isFinite())
    }
}

data class CardRect(val left: Int, val top: Int, val width: Int, val height: Int) {
    val right: Int get() = left + width
    val bottom: Int get() = top + height

    fun placement() = CardPlacement(
        left.toFloat() / IdCardComposer.PAGE_WIDTH,
        top.toFloat() / IdCardComposer.PAGE_HEIGHT,
        width.toFloat() / IdCardComposer.PAGE_WIDTH,
    )
}

object IdCardComposer {
    const val PAGE_WIDTH = 2480
    const val PAGE_HEIGHT = 3508
    private const val CARD_WIDTH = 1011
    private const val CARD_HEIGHT = 638
    private const val CARD_ASPECT = 85.6f / 53.98f
    const val MIN_FREE_WIDTH = 0.25f
    private const val MARGIN = 118

    /** Blocking. Sources are already processed and upright. */
    fun compose(front: File, back: File, output: File, layout: IdCardLayout = IdCardLayout()) {
        compose(listOf(front, back), output, layout)
    }

    fun compose(
        sources: List<File>,
        output: File,
        layout: IdCardLayout,
        sourceIndices: List<Int> = sources.indices.toList(),
    ) {
        val page = Bitmap.createBitmap(PAGE_WIDTH, PAGE_HEIGHT, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(page)
            canvas.drawColor(Color.WHITE)
            val rects = cardRects(layout, sources.map(::sourceAspect), sourceIndices)
            sources.zip(rects).forEach { (source, rect) -> drawFile(canvas, source, rect, layout.outline) }
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

    fun gapPixels(mm: Int) = (mm * 300 / 25.4).roundToInt()

    fun cardRects(
        layout: IdCardLayout,
        frontAspect: Float = CARD_ASPECT,
        backAspect: Float = CARD_ASPECT,
    ): Pair<CardRect, CardRect> {
        val rects = cardRects(layout, listOf(frontAspect, backAspect))
        return rects[0] to rects[1]
    }

    // Aspects and indices are in display order. Indices keep free placements attached to surviving sources.
    fun cardRects(
        layout: IdCardLayout,
        aspects: List<Float>,
        sourceIndices: List<Int> = aspects.indices.toList(),
    ): List<CardRect> {
        val count = aspects.size
        require(count in 1..6 && sourceIndices.size == count)
        val gap = gapPixels(layout.gapMm)
        val stacked = layout.arrangement == IdCardLayout.Arrangement.STACKED
        val large = layout.size == IdCardLayout.Size.LARGE
        val availableWidth = if (large) PAGE_WIDTH * 4 / 5 else PAGE_WIDTH - 2 * MARGIN
        val availableHeight = PAGE_HEIGHT - 2 * MARGIN
        val preferred = if (large) availableWidth else CARD_WIDTH
        val preferredHeight = (preferred * CARD_HEIGHT.toDouble() / CARD_WIDTH).roundToInt()
        val singleColumnRows = (availableHeight + gap) / (preferredHeight + gap)
        val columns = when {
            count == 1 -> 1
            stacked && count <= singleColumnRows -> 1
            else -> 2
        }
        val rows = if (stacked && columns == 2) {
            maxOf(singleColumnRows, (count + 1) / 2)
        } else {
            (count + columns - 1) / columns
        }
        val fitWidth = (availableWidth - (columns - 1) * gap) / columns
        val fitHeight = (availableHeight - (rows - 1) * gap) / rows
        val width = minOf(preferred, fitWidth, fitHeight * CARD_WIDTH / CARD_HEIGHT)
        val height = (width * CARD_HEIGHT.toDouble() / CARD_WIDTH).roundToInt()
        val left = (PAGE_WIDTH - (columns * width + (columns - 1) * gap)) / 2
        return aspects.mapIndexed { index, aspect ->
            val placement = if (layout.isFree || count == 1) layout.placementFor(sourceIndices[index]) else null
            if (placement != null) {
                placementRect(placement, aspect)
            } else {
                val slot = if (layout.reversed && count == 2) 1 - index else index
                val column = if (stacked) slot / rows else slot % columns
                val row = if (stacked) slot % rows else slot / columns
                CardRect(left + column * (width + gap), MARGIN + row * (height + gap), width, height)
            }
        }
    }

    fun placementRect(placement: CardPlacement, aspect: Float): CardRect {
        val width = (placement.width.coerceIn(MIN_FREE_WIDTH, 1f) * PAGE_WIDTH).roundToInt()
        val height = (width / aspect).roundToInt().coerceIn(1, PAGE_HEIGHT)
        val left = (placement.left.coerceIn(0f, 1f) * PAGE_WIDTH).roundToInt().coerceAtMost(PAGE_WIDTH - width)
        val top = (placement.top.coerceIn(0f, 1f) * PAGE_HEIGHT).roundToInt().coerceAtMost(PAGE_HEIGHT - height)
        return CardRect(left, top, width, height)
    }

    fun cardAspect(width: Int, height: Int): Float {
        require(width > 0 && height > 0)
        val aspect = maxOf(width, height).toFloat() / minOf(width, height)
        return if (kotlin.math.abs(aspect / CARD_ASPECT - 1f) <= 0.02f) CARD_ASPECT else aspect
    }

    private fun sourceAspect(file: File): Float {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        return cardAspect(bounds.outWidth, bounds.outHeight)
    }

    private fun drawFile(canvas: Canvas, source: File, bounds: CardRect, outline: Boolean) {
        val card = requireNotNull(BitmapFactory.decodeFile(source.path)) { "Cannot decode ${source.name}" }
        try {
            drawCard(canvas, card, bounds, outline)
        } finally {
            card.recycle()
        }
    }

    fun drawCard(canvas: Canvas, card: Bitmap, bounds: CardRect, outline: Boolean) {
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
    }
}
