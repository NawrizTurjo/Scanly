package dev.turjo.paperless.core.pdf.tools

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.multipdf.LayerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.util.Matrix
import java.io.File
import java.util.Collections
import java.util.IdentityHashMap
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

enum class NUpLayout(val perSide: Int) {
    PLAIN_2(2), PLAIN_4(4), PLAIN_6(6), PLAIN_9(9),
    CUT_EACH_SHEET(2), CUT_WHOLE_STACK(2), BOOKLET(2),
}

enum class Flip { LONG, SHORT }

data class Sheet(val front: List<Int?>, val back: List<Int?> = emptyList())

data class NUpOptions(
    val marginPt: Float = 18f,
    val gapPt: Float = 12f,
    val cutLine: Boolean = false,
    val scaleToFit: Boolean = true,
)

object NUp {
    fun impose(n: Int, layout: NUpLayout, flip: Flip): List<Sheet> {
        require(n >= 0)
        val slots = layout.perSide
        if (layout in listOf(NUpLayout.PLAIN_2, NUpLayout.PLAIN_4, NUpLayout.PLAIN_6, NUpLayout.PLAIN_9)) {
            return List(ceilDiv(n, slots)) { sheet ->
                Sheet(List(slots) { slot -> (sheet * slots + slot).takeIf { it < n } })
            }
        }
        val count = ceilDiv(n, 4)
        val padded = count.toLong() * 4
        fun side(first: Long, second: Long): List<Int?> =
            listOf(first, second).map { it.takeIf { page -> page < n }?.toInt() }
        return List(count) { index ->
            val k = index.toLong()
            val sheet = when (layout) {
                NUpLayout.CUT_EACH_SHEET -> Sheet(side(4 * k, 4 * k + 2), side(4 * k + 3, 4 * k + 1))
                NUpLayout.CUT_WHOLE_STACK ->
                    Sheet(side(2 * k, 2 * count + 2 * k), side(2 * count + 2 * k + 1, 2 * k + 1))
                NUpLayout.BOOKLET -> Sheet(side(padded - 1 - 2 * k, 2 * k), side(2 * k + 1, padded - 2 - 2 * k))
                else -> error("Expected duplex layout")
            }
            if (flip == Flip.LONG) sheet else sheet.copy(back = sheet.back.reversed())
        }
    }

    suspend fun layout(
        input: File,
        layout: NUpLayout,
        perSide: Int,
        flip: Flip,
        opts: NUpOptions,
        out: File,
    ): ToolResult = editPdf(input, out) { document, temporary ->
        val selected = when (layout) {
            NUpLayout.PLAIN_2, NUpLayout.PLAIN_4, NUpLayout.PLAIN_6, NUpLayout.PLAIN_9 ->
                when (perSide) {
                    2 -> NUpLayout.PLAIN_2
                    4 -> NUpLayout.PLAIN_4
                    6 -> NUpLayout.PLAIN_6
                    9 -> NUpLayout.PLAIN_9
                    else -> throw ToolError.Invalid("Choose 2, 4, 6 or 9 pages per side")
                }
            else -> {
                if (perSide != 2) throw ToolError.Invalid("Duplex layouts use two pages per side")
                layout
            }
        }
        val sources = document.pages.toList()
        if (sources.isEmpty()) throw ToolError.Invalid("PDF has no pages")
        val grid = Grid(selected.perSide, opts)
        val positions = IdentityHashMap<COSDictionary, Placement>()
        val layers = LayerUtility(document)
        for (side in sides(sources.size, selected, flip)) {
            val page = PDPage(grid.paper)
            document.addPage(page)
            PDPageContentStream(document, page).use { stream ->
                side.forEachIndexed { slot, index ->
                    currentCoroutineContext().ensureActive()
                    if (index != null) {
                        val source = sources[index]
                        val cell = grid.cell(slot)
                        val form = layers.importPageAsForm(document, source)
                        form.cosObject.removeItem(COSName.MATRIX)
                        form.bBox = source.cropBox
                        val placement = Placement(page, source, cell, opts.scaleToFit)
                        positions[source.cosObject] = placement
                        stream.saveGraphicsState()
                        stream.addRect(cell.lowerLeftX, cell.lowerLeftY, cell.width, cell.height)
                        stream.clip()
                        stream.transform(placement.matrix)
                        stream.drawForm(form)
                        stream.restoreGraphicsState()
                        val annotations = page.annotations
                        for (annotation in source.annotations) {
                            transformAnnotation(annotation.cosObject, placement.scale, placement::map)
                            annotation.page = page
                            if (source.rotation != 0) {
                                val rotate = COSName.getPDFName("MK")
                                // Widget appearances have their own rotation relative to the page.
                                (annotation.cosObject.getDictionaryObject(rotate) as? COSDictionary)?.let {
                                    it.setInt(COSName.R, (it.getInt(COSName.R) - source.rotation).mod(360))
                                }
                            }
                            annotations.add(annotation)
                        }
                        page.annotations = annotations
                    }
                }
                grid.drawCutLines(stream)
            }
        }
        for (page in sources) document.removePage(page)
        document.documentCatalog.cosObject.removeItem(COSName.getPDFName("PageLabels"))
        remapDestinations(document, positions, sources)
        document.save(temporary)
        ToolResult(out, document.numberOfPages)
    }

    suspend fun testSheet(layout: NUpLayout, flip: Flip, out: File): ToolResult =
        writePdfCopy(out) { temporary ->
            PDDocument().use { document ->
                val grid = Grid(layout.perSide, NUpOptions(cutLine = true))
                val duplex = layout in listOf(NUpLayout.CUT_EACH_SHEET, NUpLayout.CUT_WHOLE_STACK, NUpLayout.BOOKLET)
                val n = 2 * layout.perSide * if (duplex) 2 else 1
                val font = PDType1Font.HELVETICA_BOLD
                for (side in sides(n, layout, flip)) {
                    currentCoroutineContext().ensureActive()
                    val page = PDPage(grid.paper)
                    document.addPage(page)
                    PDPageContentStream(document, page).use { stream ->
                        side.forEachIndexed { slot, index ->
                            if (index != null) {
                                val label = (index + 1).toString()
                                val cell = grid.cell(slot)
                                val size = minOf(cell.width, cell.height) / 3
                                val width = font.getStringWidth(label) / 1000f * size
                                stream.beginText()
                                stream.setFont(font, size)
                                stream.newLineAtOffset(
                                    cell.lowerLeftX + (cell.width - width) / 2,
                                    cell.lowerLeftY + (cell.height - size * 0.7f) / 2,
                                )
                                stream.showText(label)
                                stream.endText()
                            }
                        }
                        grid.drawCutLines(stream)
                    }
                }
                document.save(temporary)
                ToolResult(out, document.numberOfPages)
            }
        }

    private fun sides(n: Int, layout: NUpLayout, flip: Flip): Sequence<List<Int?>> =
        impose(n, layout, flip).asSequence().flatMap { sheet ->
            sequenceOf(sheet.front, sheet.back).filter { it.isNotEmpty() }
        }

    private fun ceilDiv(n: Int, divisor: Int): Int = n / divisor + if (n % divisor == 0) 0 else 1

    private class Grid(slots: Int, private val options: NUpOptions) {
        val paper = if (slots == 2) PDRectangle(PDRectangle.A4.height, PDRectangle.A4.width) else PDRectangle.A4
        private val columns = if (slots == 9) 3 else 2
        private val rows = slots / columns
        private val margin = options.marginPt
        private val gap = options.gapPt
        private val width = (paper.width - 2 * margin - (columns - 1) * gap) / columns
        private val height = (paper.height - 2 * margin - (rows - 1) * gap) / rows

        init {
            if (!margin.isFinite() || !gap.isFinite() || margin < 0 || gap < 0 || width <= 0 || height <= 0) {
                throw ToolError.Invalid("Margins and gap must leave room for pages")
            }
        }

        fun cell(slot: Int): PDRectangle = PDRectangle(
            margin + (slot % columns) * (width + gap),
            paper.height - margin - height - (slot / columns) * (height + gap),
            width,
            height,
        )

        fun drawCutLines(stream: PDPageContentStream) {
            if (!options.cutLine) return
            stream.setLineWidth(0.25f)
            for (column in 1 until columns) {
                val x = margin + column * (width + gap) - gap / 2
                stream.moveTo(x, margin)
                stream.lineTo(x, paper.height - margin)
            }
            for (row in 1 until rows) {
                val y = paper.height - margin - row * (height + gap) + gap / 2
                stream.moveTo(margin, y)
                stream.lineTo(paper.width - margin, y)
            }
            stream.stroke()
        }
    }

    private class Placement(val page: PDPage, val source: PDPage, val cell: PDRectangle, fit: Boolean) {
        private val box = source.cropBox
        private val width = source.displayedWidth()
        private val height = source.displayedHeight()
        init {
            if (width <= 0 || height <= 0 || !width.isFinite() || !height.isFinite()) {
                throw ToolError.Corrupt("Invalid page box")
            }
        }
        val scale = if (fit) minOf(cell.width / width, cell.height / height) else 1f
        private val x = cell.lowerLeftX + (cell.width - width * scale) / 2
        private val y = cell.lowerLeftY + (cell.height - height * scale) / 2
        val matrix = when (source.rotation) {
            90 -> Matrix(0f, -scale, scale, 0f, x - box.lowerLeftY * scale, y + box.upperRightX * scale)
            180 -> Matrix(-scale, 0f, 0f, -scale, x + box.upperRightX * scale, y + box.upperRightY * scale)
            270 -> Matrix(0f, scale, -scale, 0f, x + box.upperRightY * scale, y - box.lowerLeftX * scale)
            else -> Matrix(scale, 0f, 0f, scale, x - box.lowerLeftX * scale, y - box.lowerLeftY * scale)
        }
        fun map(x: Float, y: Float): Pair<Float, Float> = matrix.transformPoint(x, y).let { it.x to it.y }
    }

    private suspend fun remapDestinations(
        document: PDDocument,
        positions: Map<COSDictionary, Placement>,
        sources: List<PDPage>,
    ) {
        val seen = Collections.newSetFromMap(IdentityHashMap<COSBase, Boolean>())
        val pending = ArrayDeque<COSBase>()
        pending.add(document.documentCatalog.cosObject)
        while (pending.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val node = pending.removeLast()
            if (!seen.add(node)) continue
            when (node) {
                is COSDictionary -> for (key in node.keySet()) {
                    if (node.getNameAsString(COSName.S) == "GoToR" && key == COSName.D) continue
                    node.getDictionaryObject(key)?.let(pending::add)
                }
                is COSArray -> {
                    val type = node.getName(1)
                    val first = if (node.size() > 1) node.getObject(0) else null
                    val source = when (first) {
                        is COSDictionary -> first
                        is COSNumber -> sources.getOrNull(first.intValue())?.cosObject
                        else -> null
                    }
                    val placement = positions[source]
                    if (placement != null && type in setOf("XYZ", "Fit", "FitB", "FitH", "FitBH", "FitV", "FitBV", "FitR")) {
                        retarget(node, type, placement)
                    } else {
                        for (index in 0 until node.size()) node.getObject(index)?.let(pending::add)
                    }
                }
            }
        }
    }

    private fun retarget(destination: COSArray, type: String, placement: Placement) {
        val box = placement.source.cropBox
        fun coordinate(index: Int, fallback: Float): Float =
            (destination.getObject(index) as? COSNumber)?.floatValue() ?: fallback
        val values = if (type == "XYZ") {
            val (x, y) = placement.map(coordinate(2, box.lowerLeftX), coordinate(3, box.upperRightY))
            floatArrayOf(x, y, coordinate(4, 0f))
        } else {
            val cell = placement.cell
            floatArrayOf(cell.lowerLeftX, cell.lowerLeftY, cell.upperRightX, cell.upperRightY)
        }
        destination.clear()
        destination.add(placement.page)
        destination.add(COSName.getPDFName(if (type == "XYZ") type else "FitR"))
        for (value in values) destination.add(COSFloat(value))
    }
}
