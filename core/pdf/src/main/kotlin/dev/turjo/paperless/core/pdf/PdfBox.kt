package dev.turjo.paperless.core.pdf

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

object PdfBox {
    private var initialized = false

    @Synchronized
    fun ensureInit(context: Context) {
        if (initialized) return
        PDFBoxResourceLoader.init(context.applicationContext)
        initialized = true
    }
}
