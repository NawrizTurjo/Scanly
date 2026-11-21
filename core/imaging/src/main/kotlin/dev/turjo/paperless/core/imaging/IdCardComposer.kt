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

object IdCardComposer {
    const val PAGE_WIDTH = 2480
    const val PAGE_HEIGHT = 3508
    private const val CARD_WIDTH = 1011
    private const val CARD_HEIGHT = 638

    /** Blocking. Front and back are already processed and upright. */
    fun compose(front: File, back: File, output: File) {
        val page = Bitmap.createBitmap(PAGE_WIDTH, PAGE_HEIGHT, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(page)
            canvas.drawColor(Color.WHITE)
            drawCard(canvas, front, PAGE_HEIGHT / 4)
            drawCard(canvas, back, PAGE_HEIGHT * 3 / 4)
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

    private fun drawCard(canvas: Canvas, source: File, centerY: Int) {
        val card = requireNotNull(BitmapFactory.decodeFile(source.path)) { "Cannot decode $source" }
        try {
            val left = (PAGE_WIDTH - CARD_WIDTH) / 2
            val top = centerY - CARD_HEIGHT / 2
            val destination = Rect(left, top, left + CARD_WIDTH, top + CARD_HEIGHT)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            if (card.height > card.width) {
                canvas.save()
                canvas.rotate(90f, destination.exactCenterX(), destination.exactCenterY())
                val rotatedLeft = destination.exactCenterX() - CARD_HEIGHT / 2f
                val rotatedTop = destination.exactCenterY() - CARD_WIDTH / 2f
                canvas.drawBitmap(card, null, RectF(
                    rotatedLeft, rotatedTop, rotatedLeft + CARD_HEIGHT, rotatedTop + CARD_WIDTH,
                ), paint)
                canvas.restore()
            } else {
                canvas.drawBitmap(card, null, destination, paint)
            }
        } finally {
            card.recycle()
        }
    }
}
