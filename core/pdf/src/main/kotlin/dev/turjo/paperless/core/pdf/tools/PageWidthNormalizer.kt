package dev.turjo.paperless.core.pdf.tools

import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.pdfparser.PDFStreamParser
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.abs
import kotlin.math.round

sealed interface WidthTarget {
    data object Common : WidthTarget
    data object Max : WidthTarget
    data object Min : WidthTarget
    data object A4 : WidthTarget
    data object Letter : WidthTarget
    data class Custom(val pt: Float) : WidthTarget
}

object PageWidthNormalizer {
    fun widths(input: File): List<Float> = openPdf(input).use { document ->
        if (document.isEncrypted) throw ToolError.Encrypted()
        document.pages.map(PDPage::displayedWidth)
    }

    suspend fun normalize(input: File, target: WidthTarget, out: File): ToolResult =
        editPdf(input, out) { document, temporary ->
            if (document.numberOfPages == 0) throw ToolError.Invalid("PDF has no pages")
            val widths = document.pages.map { page ->
                currentCoroutineContext().ensureActive()
                page.displayedWidth().also {
                    if (!it.isFinite() || it <= 0f) throw ToolError.Corrupt("Invalid page width")
                }
            }
            val rounded = widths.map { round(it.toDouble() * 10).toFloat() / 10f }
            val width = when (target) {
                WidthTarget.Common -> rounded.groupingBy { it }.eachCount().maxBy { it.value }.key
                WidthTarget.Max -> rounded.max()
                WidthTarget.Min -> rounded.min()
                WidthTarget.A4 -> 595f
                WidthTarget.Letter -> 612f
                is WidthTarget.Custom -> target.pt
            }
            if (!width.isFinite() || width <= 0f) throw ToolError.Invalid("Enter a positive page width")
            var scaled = 0
            document.pages.forEachIndexed { index, page ->
                currentCoroutineContext().ensureActive()
                if (abs(widths[index] - width) > 0.5f) {
                    scalePage(document, page, width / widths[index])
                    scaled++
                }
            }
            val note = if (scaled == 0) {
                input.copyTo(temporary, overwrite = true)
                "Already uniform"
            } else {
                document.save(temporary)
                "$scaled of ${document.numberOfPages} pages scaled"
            }
            ToolResult(out, document.numberOfPages, note)
        }

    private fun scalePage(document: PDDocument, page: PDPage, scale: Float) {
        val box = page.cropBox
        val x = box.lowerLeftX
        val y = box.lowerLeftY
        if (box.width <= 0 || box.height <= 0) throw ToolError.Corrupt("Invalid page box")
        val before = StringBuilder("q ${pdfNumber(scale)} 0 0 ${pdfNumber(scale)} ${pdfNumber(-x * scale)} ${pdfNumber(-y * scale)} cm\n")
        val after = StringBuilder("\n")
        val parser = PDFStreamParser(page)
        var depth = 0
        var minimum = 0
        while (true) {
            val token = parser.parseNextToken() ?: break
            when ((token as? Operator)?.name) {
                "q" -> depth++
                "Q" -> {
                    depth--
                    minimum = minOf(minimum, depth)
                }
            }
        }
        repeat(-minimum) { before.append("q\n") }
        repeat(depth - minimum + 1) { after.append("Q\n") }

        val streams = buildList {
            add(PDStream(document, before.toString().byteInputStream()))
            page.contentStreams.forEachRemaining { add(it) }
            add(PDStream(document, after.toString().byteInputStream()))
        }
        page.setContents(streams)
        page.mediaBox = PDRectangle(box.width * scale, box.height * scale)
        page.cosObject.removeItem(COSName.CROP_BOX)
        val inheritedCrop = page.cropBox
        if (inheritedCrop.lowerLeftX != 0f || inheritedCrop.lowerLeftY != 0f ||
            inheritedCrop.width != page.mediaBox.width || inheritedCrop.height != page.mediaBox.height
        ) {
            // An inherited CropBox must be overridden on this page only.
            page.cropBox = page.mediaBox
        }
        for (key in listOf(COSName.BLEED_BOX, COSName.TRIM_BOX, COSName.ART_BOX)) {
            page.cosObject.removeItem(key)
        }
        for (annotation in page.annotations) {
            transformAnnotation(annotation.cosObject, scale) { px, py ->
                (px - x) * scale to (py - y) * scale
            }
        }
    }

    private fun pdfNumber(value: Float): String =
        BigDecimal.valueOf(value.toDouble()).setScale(6, RoundingMode.HALF_EVEN).stripTrailingZeros().toPlainString()
}

internal fun PDPage.displayedWidth(): Float = if (rotation % 180 == 0) cropBox.width else cropBox.height
internal fun PDPage.displayedHeight(): Float = if (rotation % 180 == 0) cropBox.height else cropBox.width

internal fun transformAnnotation(
    annotation: COSDictionary,
    scale: Float,
    map: (Float, Float) -> Pair<Float, Float>,
) {
    fun transform(array: COSArray): COSArray {
        val result = COSArray()
        for (index in 0 until array.size() - 1 step 2) {
            val x = (array.getObject(index) as? COSNumber)?.floatValue() ?: return array
            val y = (array.getObject(index + 1) as? COSNumber)?.floatValue() ?: return array
            val (px, py) = map(x, y)
            result.add(COSFloat(px))
            result.add(COSFloat(py))
        }
        return if (result.size() == array.size()) result else array
    }
    for (name in listOf("Rect", "QuadPoints", "Vertices", "L", "CL")) {
        val key = COSName.getPDFName(name)
        val array = annotation.getDictionaryObject(key) as? COSArray ?: continue
        val mapped = transform(array)
        if (name == "Rect" && mapped.size() == 4) {
            annotation.setItem(key, PDRectangle(mapped).cosObject)
        } else {
            annotation.setItem(key, mapped)
        }
    }
    val inkKey = COSName.getPDFName("InkList")
    (annotation.getDictionaryObject(inkKey) as? COSArray)?.let { ink ->
        val strokes = COSArray()
        for (index in 0 until ink.size()) {
            val stroke = ink.getObject(index)
            strokes.add(if (stroke is COSArray) transform(stroke) else ink.get(index))
        }
        annotation.setItem(inkKey, strokes)
    }
    val marginKey = COSName.getPDFName("RD")
    (annotation.getDictionaryObject(marginKey) as? COSArray)?.let { margins ->
        annotation.setItem(marginKey, COSArray().apply {
            for (value in margins.toFloatArray()) add(COSFloat(value * scale))
        })
    }
}
