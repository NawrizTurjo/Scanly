package dev.turjo.paperless.core.pdf

import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import dev.turjo.paperless.core.model.OutlineNode
import dev.turjo.paperless.core.pdf.tools.openPdf
import java.io.File
import java.io.Writer
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

object ChapterSuggester {
    private val headingPattern = Regex("""^(?:\d+(?:\.\d+)*\s|Chapter\s)""", RegexOption.IGNORE_CASE)
    private val spaces = Regex("""\s+""")

    suspend fun suggest(file: File): List<OutlineNode> = withContext(Dispatchers.IO) {
        openPdf(file).use { document ->
            val context = currentCoroutineContext()
            val stripper = LineStripper { context.ensureActive() }
            stripper.writeText(document, object : Writer() {
                override fun write(buffer: CharArray, offset: Int, length: Int) = Unit
                override fun flush() = Unit
                override fun close() = Unit
            })
            val lines = stripper.lines
            val bodySize = lines.groupingBy { it.size }.eachCount().maxByOrNull { it.value }?.key
                ?: return@use emptyList()
            val repeated = lines.groupBy { it.normalized }.filterValues { copies ->
                val pageCount = copies.map { it.page }.distinct().size
                pageCount > 1 && pageCount > document.numberOfPages / 2
            }.keys

            // PdfBox reports slightly different sizes for runs that use the same heading style.
            val clusters = mutableListOf<Float>()
            val sizes = lines.filterNot { it.normalized in repeated }.map { it.size }.distinct().sortedDescending()
            for (size in sizes) {
                if (size < bodySize * 1.15f) break
                if (clusters.none { size >= it * 0.95f }) clusters.add(size)
                if (clusters.size == 3) break
            }
            val roots = mutableListOf<Heading>()
            val parents = mutableListOf<Heading>()
            val seen = mutableSetOf<String>()
            for (line in lines) {
                val level = clusters.indexOfFirst { line.size <= it && line.size >= it * 0.95f }
                if (level < 0 || line.normalized in repeated) continue
                if (!line.firstOnPage && !headingPattern.containsMatchIn(line.title) && !line.shortCaps) continue
                if (!seen.add(line.normalized)) continue
                while (parents.isNotEmpty() && parents.last().level >= level) parents.removeAt(parents.lastIndex)
                val heading = Heading(line, level)
                (parents.lastOrNull()?.children ?: roots).add(heading)
                parents.add(heading)
            }
            roots.map(Heading::toNode)
        }
    }

    private data class Heading(val line: Line, val level: Int, val children: MutableList<Heading> = mutableListOf()) {
        fun toNode(): OutlineNode = OutlineNode(line.title, line.page, children.map(Heading::toNode))
    }

    private data class Line(val title: String, val page: Int, val size: Float, val firstOnPage: Boolean) {
        val normalized = title.replace(spaces, " ").lowercase()
        val shortCaps = title.length <= 60 && title.any(Char::isUpperCase) && title.none(Char::isLowerCase)
    }

    private class LineStripper(private val checkCancelled: () -> Unit) : PDFTextStripper() {
        val lines = mutableListOf<Line>()
        private val text = StringBuilder()
        private val styles = mutableMapOf<Style, Int>()
        private var firstOnPage = true

        init {
            sortByPosition = true
        }

        override fun startPage(page: PDPage) {
            checkCancelled()
            firstOnPage = true
        }

        override fun endPage(page: PDPage) = finishLine()

        override fun writeString(value: String, positions: MutableList<TextPosition>) {
            text.append(value)
            for (position in positions) {
                val size = (position.fontSizeInPt * 2f).roundToInt() / 2f
                if (size <= 0f) continue
                val font = position.font
                val bold = font.fontDescriptor?.isForceBold == true || font.name.orEmpty().contains("bold", true)
                val style = Style(size, bold)
                styles[style] = (styles[style] ?: 0) + 1
            }
        }

        override fun writeWordSeparator() {
            text.append(' ')
        }

        override fun writeLineSeparator() = finishLine()

        private fun finishLine() {
            val title = text.toString().trim()
            val style = styles.maxWithOrNull(compareBy({ it.value }, { it.key.bold }))?.key
            if (title.isNotEmpty() && style != null) {
                lines.add(Line(title, currentPageNo - 1, style.size, firstOnPage))
                firstOnPage = false
            }
            text.clear()
            styles.clear()
        }

        private data class Style(val size: Float, val bold: Boolean)
    }
}
