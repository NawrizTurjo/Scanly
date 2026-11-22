package dev.turjo.paperless.core.pdf.tools

import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.PDFCloneUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDDocumentInformation
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

object PdfSplit {
    suspend fun byRanges(input: File, ranges: List<IntRange>, outDir: File): List<ToolResult> =
        split(input, outDir) { ranges }

    suspend fun everyN(input: File, n: Int, outDir: File): List<ToolResult> =
        split(input, outDir) { count ->
            requireTool(n > 0, "Pages per file must be positive")
            (0 until count step n).map { first -> first until (first + minOf(n, count - first)) }
        }

    private suspend fun split(
        input: File,
        outDir: File,
        getRanges: (Int) -> List<IntRange>,
    ): List<ToolResult> = withContext(Dispatchers.IO) {
        val count = openPdf(input).use { it.numberOfPages }
        val ranges = getRanges(count)
        requireTool(ranges.isNotEmpty() && ranges.all { !it.isEmpty() && it.first >= 0 && it.last < count },
            "Invalid split ranges")
        outputDirectory(outDir)
        writeSeries { created ->
            ranges.mapIndexed { index, range ->
                ensureActive()
                val out = File(outDir, "${input.nameWithoutExtension}-part${(index + 1).toString().padStart(3, '0')}.pdf")
                openPdf(input).use { source ->
                    val originals = source.pages.toList()
                    val kept = range.map { originals[it] }
                    PageNavigation(source, originals).retain(kept)
                    replacePages(source, kept)
                    PDDocument(MemoryUsageSetting.setupTempFileOnly()).use { part ->
                        // Import the trimmed graph together so links and bookmarks share the imported page objects.
                        val cloner = PDFCloneUtility(part)
                        val catalog = cloner.cloneForNewDocument(source.documentCatalog) as COSDictionary
                        part.documentCatalog.cosObject.addAll(catalog)
                        part.version = source.version
                        part.documentInformation = PDDocumentInformation(
                            cloner.cloneForNewDocument(source.documentInformation) as COSDictionary,
                        )
                        writeNewFile(out, part::save)
                        created += out
                    }
                }
                ToolResult(out, range.count())
            }
        }
    }
}
