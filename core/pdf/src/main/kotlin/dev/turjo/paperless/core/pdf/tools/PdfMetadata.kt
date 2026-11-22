package dev.turjo.paperless.core.pdf.tools

import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class DocInfo(
    val title: String? = null,
    val author: String? = null,
    val subject: String? = null,
    val keywords: String? = null,
)

object PdfMetadata {
    fun read(input: File): DocInfo = openPdf(input).use {
        val info = it.documentInformation
        DocInfo(info.title, info.author, info.subject, info.keywords)
    }

    suspend fun write(input: File, info: DocInfo?, clearAll: Boolean, out: File): ToolResult =
        editPdf(input, out) { document, temporary ->
            if (clearAll) {
                document.documentCatalog.metadata = null
                document.document.trailer.removeItem(COSName.INFO)
                for (page in document.pages) {
                    currentCoroutineContext().ensureActive()
                    page.metadata = null
                }
            } else if (info != null) {
                document.documentInformation.apply {
                    title = info.title
                    author = info.author
                    subject = info.subject
                    keywords = info.keywords
                }
            }
            document.save(temporary)
            ToolResult(out, document.numberOfPages)
        }
}

internal fun openPdf(input: File, password: String = ""): PDDocument = try {
    PDDocument.load(input, password)
} catch (_: InvalidPasswordException) {
    throw ToolError.Encrypted()
} catch (error: IOException) {
    throw ToolError.Corrupt(error.message ?: "Cannot read PDF")
}

internal suspend fun editPdf(
    input: File,
    out: File,
    password: String? = null,
    edit: suspend (PDDocument, File) -> ToolResult,
): ToolResult = writePdfCopy(out, input) { temporary ->
    openPdf(input, password.orEmpty()).use { document ->
        if (password == null && document.isEncrypted) throw ToolError.Encrypted()
        edit(document, temporary)
    }
}

internal suspend fun writePdfCopy(
    out: File,
    input: File? = null,
    write: suspend (File) -> ToolResult,
): ToolResult = withContext(Dispatchers.IO) {
    if (input != null && (input.canonicalFile == out.canonicalFile ||
            out.exists() && Files.isSameFile(input.toPath(), out.toPath()))) {
        throw ToolError.Invalid("Choose a separate output file")
    }
    val temporary = Files.createTempFile(out.absoluteFile.parentFile!!.toPath(), ".scanly-", ".pdf").toFile()
    try {
        val result = write(temporary)
        ensureActive()
        Files.move(temporary.toPath(), out.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        result
    } finally {
        temporary.delete()
    }
}
