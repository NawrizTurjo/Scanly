package dev.turjo.paperless.core.pdf.tools

import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.util.Matrix
import java.io.File
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

enum class NumberStyle { NUMBER, FRACTION, PAGE_NUMBER }
enum class NumberPosition { TOP_LEFT, TOP_CENTER, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_CENTER, BOTTOM_RIGHT }

object PageNumbers {
    suspend fun stamp(
        input: File,
        style: NumberStyle,
        position: NumberPosition,
        fontSize: Float,
        startAt: Int,
        pages: IntRange?,
        out: File,
    ): ToolResult = editPdf(input, out) { document, temporary ->
        if (!fontSize.isFinite() || fontSize <= 0f) throw ToolError.Invalid("Enter a positive font size")
        val range = pages ?: (0 until document.numberOfPages)
        if (range.isEmpty() || range.first < 0 || range.last >= document.numberOfPages) {
            throw ToolError.Invalid("Page range is outside the PDF")
        }
        val font = PDType1Font.HELVETICA
        range.forEachIndexed { offset, index ->
            currentCoroutineContext().ensureActive()
            val number = startAt.toLong() + offset
            val label = when (style) {
                NumberStyle.NUMBER -> number.toString()
                NumberStyle.FRACTION -> "$number / ${document.numberOfPages}"
                NumberStyle.PAGE_NUMBER -> "Page $number"
            }
            val page = document.getPage(index)
            val width = font.getStringWidth(label) / 1000f * fontSize
            val x = when (position) {
                NumberPosition.TOP_LEFT, NumberPosition.BOTTOM_LEFT -> 18f
                NumberPosition.TOP_CENTER, NumberPosition.BOTTOM_CENTER -> (page.displayedWidth() - width) / 2
                NumberPosition.TOP_RIGHT, NumberPosition.BOTTOM_RIGHT -> page.displayedWidth() - width - 18f
            }
            val y = when (position) {
                NumberPosition.TOP_LEFT, NumberPosition.TOP_CENTER, NumberPosition.TOP_RIGHT ->
                    page.displayedHeight() - fontSize - 18f
                else -> 18f
            }
            PDPageContentStream(document, page, PDPageContentStream.AppendMode.APPEND, true, true).use {
                it.beginText()
                it.setFont(font, fontSize)
                it.setTextMatrix(page.displayToPage(x, y))
                it.showText(label)
                it.endText()
            }
        }
        document.save(temporary)
        ToolResult(out, document.numberOfPages)
    }
}

private fun PDPage.displayToPage(x: Float, y: Float): Matrix {
    val box = cropBox
    return when (rotation) {
        90 -> Matrix(0f, 1f, -1f, 0f, box.upperRightX - y, box.lowerLeftY + x)
        180 -> Matrix(-1f, 0f, 0f, -1f, box.upperRightX - x, box.upperRightY - y)
        270 -> Matrix(0f, -1f, 1f, 0f, box.lowerLeftX + y, box.upperRightY - x)
        else -> Matrix(1f, 0f, 0f, 1f, box.lowerLeftX + x, box.lowerLeftY + y)
    }
}
