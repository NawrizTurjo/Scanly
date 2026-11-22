package dev.turjo.paperless.core.pdf

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PointF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.graphics.blend.BlendMode
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.util.Matrix
import dev.turjo.paperless.core.model.Annotation
import dev.turjo.paperless.core.model.AnnotationType
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

object AnnotationFlattener {
    suspend fun flatten(input: File, annotations: List<Annotation>, out: File) = withContext(Dispatchers.IO) {
        require(input.canonicalFile != out.canonicalFile)
        require(!out.exists() || out.length() == 0L)
        try {
            PDDocument.load(input, MemoryUsageSetting.setupTempFileOnly()).use { document ->
                val highlight = PDExtendedGraphicsState().apply {
                    nonStrokingAlphaConstant = 0.35f
                    strokingAlphaConstant = 0.35f
                    blendMode = BlendMode.MULTIPLY
                }
                for ((index, marks) in annotations.groupBy { it.page }) {
                    ensureActive()
                    val page = document.getPage(index)
                    val box = page.cropBox
                    val sideways = page.rotation == 90 || page.rotation == 270
                    val width = if (sideways) box.height else box.width
                    val height = if (sideways) box.width else box.height
                    PDPageContentStream(document, page, PDPageContentStream.AppendMode.APPEND, true, true).use { stream ->
                        stream.saveGraphicsState()
                        stream.transform(page.displayToUser())
                        for (mark in marks) {
                            stream.saveGraphicsState()
                            if (mark.type == AnnotationType.HIGHLIGHT) stream.setGraphicsStateParameters(highlight)
                            stream.drawMark(document, mark, width, height)
                            stream.restoreGraphicsState()
                        }
                        stream.restoreGraphicsState()
                    }
                }
                ensureActive()
                document.save(out)
            }
        } catch (error: Exception) {
            out.delete()
            throw error
        }
    }

    private fun PDPage.displayToUser(): Matrix {
        // The reader reports points relative to the visible CropBox, which defaults to the MediaBox.
        val box = cropBox
        val x = box.lowerLeftX
        val y = box.lowerLeftY
        return when (rotation) {
            90 -> Matrix(0f, 1f, 1f, 0f, x, y)
            180 -> Matrix(-1f, 0f, 0f, 1f, box.upperRightX, y)
            270 -> Matrix(0f, -1f, -1f, 0f, box.upperRightX, box.upperRightY)
            else -> Matrix(1f, 0f, 0f, -1f, x, box.upperRightY)
        }
    }

    private fun PDPageContentStream.drawMark(document: PDDocument, mark: Annotation, width: Float, height: Float) {
        val red = (mark.color shr 16 and 255) / 255f
        val green = (mark.color shr 8 and 255) / 255f
        val blue = (mark.color and 255) / 255f
        setStrokingColor(red, green, blue)
        setNonStrokingColor(red, green, blue)
        setLineWidth(mark.width)
        setLineCapStyle(1)
        setLineJoinStyle(1)
        if (mark.type == AnnotationType.NOTE) {
            val anchor = mark.points.firstOrNull() ?: return
            val x = anchor.x.coerceIn(0f, (width - 9f).coerceAtLeast(0f))
            val y = anchor.y.coerceIn(0f, (height - 9f).coerceAtLeast(0f))
            addRect(x, y, 9f, 9f)
            fill()
            setStrokingColor(1f, 1f, 1f)
            setLineWidth(1f)
            for (line in listOf(3f, 6f)) {
                moveTo(x + 2f, y + line)
                lineTo(x + 7f, y + line)
                stroke()
            }
            val text = mark.note.orEmpty()
            if (text.isNotBlank()) drawNote(document, PointF(x, y), text, width, height)
            return
        }
        if (mark.points.isNotEmpty()) {
            val first = mark.points.first()
            val last = mark.points.last()
            moveTo(first.x, first.y)
            if (mark.points.size == 1) lineTo(first.x + 0.01f, first.y)
            var x = first.x
            var y = first.y
            for ((control, next) in mark.points.zipWithNext()) {
                val endX = (control.x + next.x) / 2
                val endY = (control.y + next.y) / 2
                curveTo(
                    x + (control.x - x) * 2 / 3, y + (control.y - y) * 2 / 3,
                    endX + (control.x - endX) * 2 / 3, endY + (control.y - endY) * 2 / 3,
                    endX, endY,
                )
                x = endX
                y = endY
            }
            lineTo(last.x, last.y)
            stroke()
        }
        for (rect in mark.rects) {
            when (mark.type) {
                AnnotationType.HIGHLIGHT -> {
                    addRect(rect.left, rect.top, rect.right - rect.left, rect.bottom - rect.top)
                    fill()
                }
                AnnotationType.UNDERLINE, AnnotationType.STRIKE -> {
                    val y = if (mark.type == AnnotationType.UNDERLINE) rect.bottom else (rect.top + rect.bottom) / 2
                    moveTo(rect.left, y)
                    lineTo(rect.right, y)
                    stroke()
                }
                else -> Unit
            }
        }
    }

    private fun PDPageContentStream.drawNote(
        document: PDDocument,
        anchor: PointF,
        text: String,
        pageWidth: Float,
        pageHeight: Float,
    ) {
        // Android lays out Unicode with the system fonts without changing the PDF text layer.
        val paint = TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textSize = 21f; color = 0xFF202020.toInt() }
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, 270)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).build()
        val bitmap = Bitmap.createBitmap(282, layout.height + 12, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(0xFFFFF1B8.toInt())
            canvas.translate(6f, 6f)
            layout.draw(canvas)
            val image = LosslessFactory.createFromImage(document, bitmap)
            val scale = minOf(1 / 3f, pageWidth / bitmap.width, pageHeight / bitmap.height)
            val width = bitmap.width * scale
            val height = bitmap.height * scale
            val x = if (anchor.x + 11f + width <= pageWidth) anchor.x + 11f else (anchor.x - width - 2f).coerceAtLeast(0f)
            val y = anchor.y.coerceAtMost((pageHeight - height).coerceAtLeast(0f))
            drawImage(image, x, y + height, width, -height)
        } finally {
            bitmap.recycle()
        }
    }
}
