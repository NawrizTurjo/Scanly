package dev.turjo.paperless.core.pdf

import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import com.tom_roush.pdfbox.util.Matrix
import dev.turjo.paperless.core.ocr.RecognizedWord

// Recognition reads the rendered JPEG, so page edits and rotation are already in these pixels.
internal fun placeWord(word: RecognizedWord, source: ExportPage, image: PagePlacement): PagePlacement {
    val sx = image.w / source.widthPx
    val sy = image.h / source.heightPx
    return PagePlacement(
        image.x + word.left * sx,
        image.y + (source.heightPx - word.bottom) * sy,
        (word.right - word.left) * sx,
        (word.bottom - word.top) * sy,
    )
}

internal fun encodableWidth(font: PDFont, word: String): Float? = try {
    (font.getStringWidth(word) / 1000f).takeIf { it > 0 }
} catch (_: IllegalArgumentException) {
    null
}

internal fun writeSearchableText(
    stream: PDPageContentStream,
    source: ExportPage,
    image: PagePlacement,
    font: PDFont,
) {
    stream.beginText()
    stream.setRenderingMode(RenderingMode.NEITHER)
    stream.setFont(font, 1f)
    val metrics = font.fontDescriptor
    val glyphHeight = (metrics.capHeight - metrics.descent) / 1000f
    for (word in source.words) {
        val naturalWidth = encodableWidth(font, word.text) ?: continue
        val box = placeWord(word, source, image)
        if (box.w <= 0 || box.h <= 0) continue
        val verticalScale = box.h / glyphHeight
        val baseline = box.y - metrics.descent / 1000f * verticalScale
        stream.setTextMatrix(Matrix(box.w / naturalWidth, 0f, 0f, verticalScale, box.x, baseline))
        stream.showText(word.text)
    }
    stream.endText()
}
