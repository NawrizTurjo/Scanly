package dev.turjo.paperless.core.pdf

import android.graphics.Bitmap
import android.graphics.PointF
import android.util.SizeF
import dev.turjo.paperless.core.model.PageLink
import dev.turjo.paperless.core.model.PageRect
import dev.turjo.paperless.core.model.PdfKey
import dev.turjo.paperless.core.model.TextMatch
import java.io.IOException

interface PdfDocumentHandle {
    val pageCount: Int
    suspend fun pageSize(index: Int): SizeF
    suspend fun render(index: Int, widthPx: Int, night: Boolean = false): Bitmap
    suspend fun search(query: String): List<TextMatch> = emptyList()
    suspend fun pageText(index: Int): String = ""
    suspend fun select(index: Int, start: PointF, end: PointF): Pair<String, List<PageRect>>? = null
    suspend fun links(index: Int): List<PageLink> = emptyList()
    suspend fun contentBox(index: Int): PageRect =
        pageSize(index).let { PageRect(0f, 0f, it.width, it.height) }
}

interface PdfSessionManager {
    suspend fun open(key: PdfKey): PdfDocumentHandle
    fun release(key: PdfKey)
}

sealed class PdfOpenException(message: String, cause: Throwable) : IOException(message, cause) {
    class Encrypted(cause: Throwable) : PdfOpenException("PDF requires a password", cause)
    class Corrupt(cause: Throwable) : PdfOpenException("PDF could not be read", cause)
}
