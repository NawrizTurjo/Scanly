package dev.turjo.paperless.core.pdf.tools

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import java.io.File
import java.util.Collections
import java.util.IdentityHashMap
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

object PdfSanitize {
    suspend fun sanitize(input: File, removeXmp: Boolean, out: File): ToolResult =
        editPdf(input, out) { document, temporary ->
            val catalog = document.documentCatalog.cosObject
            catalog.removeItem(COSName.OPEN_ACTION)
            (catalog.getDictionaryObject(COSName.NAMES) as? COSDictionary)?.apply {
                removeItem(COSName.JAVA_SCRIPT)
                removeItem(COSName.EMBEDDED_FILES)
            }
            val seen = Collections.newSetFromMap(IdentityHashMap<COSBase, Boolean>())
            val pending = ArrayDeque<COSBase>()
            pending.add(catalog)
            while (pending.isNotEmpty()) {
                currentCoroutineContext().ensureActive()
                val node = pending.removeLast()
                if (!seen.add(node)) continue
                when (node) {
                    is COSDictionary -> {
                        node.removeItem(COSName.AA)
                        node.removeItem(COSName.getPDFName("AF"))
                        if (removeXmp) node.removeItem(COSName.METADATA)
                        for (key in node.keySet().toList()) {
                            val child = node.getDictionaryObject(key) ?: continue
                            if (shouldRemove(child)) node.removeItem(key) else pending.add(child)
                        }
                    }
                    is COSArray -> for (index in node.size() - 1 downTo 0) {
                        val child = node.getObject(index) ?: continue
                        if (shouldRemove(child)) node.remove(index) else pending.add(child)
                    }
                }
            }
            document.save(temporary)
            ToolResult(out, document.numberOfPages)
        }

    private fun shouldRemove(value: COSBase?): Boolean =
        value is COSDictionary &&
            (value.getNameAsString(COSName.S) == "JavaScript" ||
                value.getNameAsString(COSName.SUBTYPE) == "FileAttachment" ||
                value.getNameAsString(COSName.TYPE) == "EmbeddedFile")
}
