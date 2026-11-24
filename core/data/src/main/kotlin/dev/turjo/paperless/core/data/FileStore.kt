package dev.turjo.paperless.core.data

import android.content.Context
import android.util.AtomicFile
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex

@Singleton
class FileStore @Inject constructor(@ApplicationContext private val context: Context) {
    internal val pageCreationLock = Mutex()

    fun pageDir(pageId: String): File {
        require(pageId.isNotEmpty() && pageId != "." && pageId != "..")
        require('/' !in pageId && '\\' !in pageId)
        return File(context.filesDir, "pages/$pageId")
    }

    internal fun signaturePlacements(pageId: String) = File(pageDir(pageId), "signatures.json")

    internal fun pageWithSignatures(row: PageEntity) =
        row.toModel().copy(signatures = signaturePlacements(row.id).readSignatures())

    fun originalFile(pageId: String) = File(pageDir(pageId), "orig.jpg")
    fun processedFile(pageId: String) = File(pageDir(pageId), "proc.jpg")
    fun thumbFile(pageId: String) = File(pageDir(pageId), "thumb.webp")

    internal fun duplexFile(sessionId: String): AtomicFile {
        require(sessionId.isNotEmpty() && '/' !in sessionId && '\\' !in sessionId)
        return AtomicFile(File(context.filesDir, "duplex/$sessionId.json"))
    }

    fun deletePageFiles(pageId: String) {
        val directory = pageDir(pageId)
        if (!directory.deleteRecursively()) throw IOException("Could not delete $directory")
    }

    internal fun deleteRestoredPdf(documentId: String) {
        val directory = restoredReaderPdfDir(context, documentId)
        if (!directory.deleteRecursively()) throw IOException("Could not delete restored PDF for $documentId")
    }

    internal fun signatureImage(id: String) = placedSignatureFile(context, id)

    fun newTempFile(suffix: String): File {
        val directory = File(context.cacheDir, "tmp")
        directory.mkdirs()
        return File.createTempFile("scan-", suffix, directory)
    }
}
