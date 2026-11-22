package dev.turjo.paperless.core.pdf

import android.content.Context
import dev.turjo.paperless.core.pdf.tools.openPdf
import java.io.File

object PdfInfo {
    fun pageCount(context: Context, file: File): Int {
        PdfBox.ensureInit(context)
        return openPdf(file).use { it.numberOfPages }
    }
}
