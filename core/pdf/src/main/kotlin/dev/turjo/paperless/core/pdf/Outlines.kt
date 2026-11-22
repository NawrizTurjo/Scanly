package dev.turjo.paperless.core.pdf

import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitWidthDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode
import dev.turjo.paperless.core.model.OutlineNode
import dev.turjo.paperless.core.pdf.tools.editPdf
import dev.turjo.paperless.core.pdf.tools.openPdf
import dev.turjo.paperless.core.pdf.tools.ToolResult
import java.io.File
import java.util.Collections
import java.util.IdentityHashMap
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

object Outlines {
    /** Call on an I/O dispatcher after initializing [PdfBox]. */
    fun read(file: File): List<OutlineNode> = openPdf(file).use { document ->
        val outline = document.documentCatalog.documentOutline ?: return@use emptyList()
        val pages = document.pages
        val visited = Collections.newSetFromMap(IdentityHashMap<COSDictionary, Boolean>())
        fun readChildren(node: PDOutlineNode): List<OutlineNode> = buildList {
            for (item in node.children()) {
                if (!visited.add(item.cosObject)) break
                val page = item.findDestinationPage(document)?.let(pages::indexOf) ?: -1
                val children = readChildren(item)
                if (page in 0 until document.numberOfPages) {
                    add(OutlineNode(item.title.orEmpty(), page, children))
                } else {
                    addAll(children)
                }
            }
        }
        readChildren(outline)
    }

    suspend fun write(input: File, outline: List<OutlineNode>, out: File) {
        editPdf(input, out) { document, temporary ->
            val root = PDDocumentOutline()
            for (node in outline) root.addLast(createItem(document, node))
            root.openNode()
            document.documentCatalog.documentOutline = root
            document.save(temporary)
            ToolResult(out, document.numberOfPages)
        }
    }

    private suspend fun createItem(document: PDDocument, node: OutlineNode): PDOutlineItem {
        currentCoroutineContext().ensureActive()
        require(node.page in 0 until document.numberOfPages) { "Bookmark page is outside the document" }
        return PDOutlineItem().apply {
            title = node.title
            setDestination(PDPageFitWidthDestination().apply { page = document.getPage(node.page) })
            for (child in node.children) addLast(createItem(document, child))
        }
    }
}
