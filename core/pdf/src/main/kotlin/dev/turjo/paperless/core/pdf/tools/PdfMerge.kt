package dev.turjo.paperless.core.pdf.tools

import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.PDFCloneUtility
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

object PdfMerge {
    suspend fun merge(inputs: List<File>, out: File): ToolResult = withContext(Dispatchers.IO) {
        requireTool(inputs.isNotEmpty(), "Pick at least one PDF")
        PDDocument(MemoryUsageSetting.setupTempFileOnly()).use { merged ->
            val merger = PDFMergerUtility()
            val outline = PDDocumentOutline()
            for (input in inputs) {
                ensureActive()
                openPdf(input).use { source ->
                    val sourcePages = source.pages.toList()
                    val navigation = PageNavigation(source, sourcePages)
                    val sourceOutline = source.documentCatalog.documentOutline
                    source.documentCatalog.documentOutline = null
                    val first = merged.numberOfPages
                    merger.appendDocument(merged, source)
                    val imported = merged.pages.toList().drop(first)
                    val mapping = sourcePages.zip(imported).associate { (old, new) -> old.cosObject to new.cosObject }
                    for ((old, new) in sourcePages.zip(imported)) {
                        ensureActive()
                        val links = old.annotations.filterIsInstance<PDAnnotationLink>()
                        val copies = new.annotations.filterIsInstance<PDAnnotationLink>()
                        for ((link, copy) in links.zip(copies)) {
                            val target = navigation.target(link.cosObject) ?: continue
                            val page = mapping[target.page] ?: continue
                            val owner = if (target.owner === link.cosObject) copy.cosObject
                                else copy.cosObject.getCOSDictionary(COSName.A)
                            target.writeTo(owner, page)
                        }
                    }
                    val entry = PDOutlineItem().apply {
                        title = input.name
                        setDestination(imported.first())
                    }
                    if (sourceOutline != null) {
                        rebuildOutline(sourceOutline, entry, navigation, mapping::get, PDFCloneUtility(merged))
                    }
                    outline.addLast(entry)
                }
            }
            outline.openNode()
            merged.documentCatalog.documentOutline = outline
            writeNewFile(out, merged::save)
            ToolResult(out, merged.numberOfPages)
        }
    }
}
