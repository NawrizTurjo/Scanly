package dev.turjo.paperless.core.pdf

import android.graphics.Bitmap
import android.util.SizeF
import dev.turjo.paperless.core.model.PdfKey
import java.io.IOException

interface PdfDocumentHandle {
    val pageCount: Int
    suspend fun pageSize(index: Int): SizeF
    suspend fun render(index: Int, widthPx: Int, night: Boolean = false): Bitmap
}

interface PdfSessionManager {
    suspend fun open(key: PdfKey): PdfDocumentHandle
    fun release(key: PdfKey)
}

sealed class PdfOpenException(message: String, cause: Throwable) : IOException(message, cause) {
    class Encrypted(cause: Throwable) : PdfOpenException("PDF requires a password", cause)
    class Corrupt(cause: Throwable) : PdfOpenException("PDF could not be read", cause)
}
