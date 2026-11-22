package dev.turjo.paperless.core.pdf.tools

import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.io.RandomAccessBufferedFileInputStream
import com.tom_roush.pdfbox.io.ScratchFile
import com.tom_roush.pdfbox.pdfparser.PDFParser
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

data class ToolResult(val output: File, val pages: Int, val note: String? = null)

sealed class ToolError(msg: String) : Exception(msg) {
    class Encrypted : ToolError("Password-protected")
    class Corrupt(m: String) : ToolError(m)
    class Invalid(m: String) : ToolError(m)
}

internal fun requireTool(condition: Boolean, message: String) {
    if (!condition) throw ToolError.Invalid(message)
}

internal fun openPdf(file: File): PDDocument {
    val document = try {
        loadPdf(file)
    } catch (_: InvalidPasswordException) {
        throw ToolError.Encrypted()
    } catch (error: IOException) {
        throw ToolError.Corrupt("Cannot read ${file.name}: ${error.message}").apply { initCause(error) }
    }
    if (document.isEncrypted) {
        document.close()
        throw ToolError.Encrypted()
    }
    if (document.numberOfPages == 0) {
        document.close()
        throw ToolError.Corrupt("No pages in ${file.name}")
    }
    return document
}

private fun loadPdf(file: File): PDDocument {
    val source = RandomAccessBufferedFileInputStream(file)
    try {
        val scratch = ScratchFile(MemoryUsageSetting.setupTempFileOnly())
        try {
            // Lenient parsing can reconstruct a page tree from an incomplete download.
            val parser = PDFParser(source, scratch)
            parser.isLenient = false
            parser.parse()
            return parser.pdDocument
        } catch (failure: Throwable) {
            runCatching { scratch.close() }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
    } catch (failure: Throwable) {
        runCatching { source.close() }.exceptionOrNull()?.let(failure::addSuppressed)
        throw failure
    }
}

internal fun outputDirectory(dir: File) {
    if (!dir.mkdirs() && !dir.isDirectory) throw IOException("Cannot create directory: ${dir.name}")
}

internal suspend fun <T> writeNewFile(out: File, write: (File) -> T): T {
    requireTool(!out.exists(), "Output already exists: ${out.name}")
    val temp = File.createTempFile("pdf-tool-", ".tmp", out.absoluteFile.parentFile)
    try {
        coroutineContext.ensureActive()
        val result = write(temp)
        coroutineContext.ensureActive()
        Files.move(temp.toPath(), out.toPath())
        return result
    } finally {
        temp.delete()
    }
}

internal suspend fun <T> writeSeries(block: suspend (MutableList<File>) -> T): T {
    val created = mutableListOf<File>()
    try {
        return block(created)
    } catch (failure: Exception) {
        created.forEach { it.delete() }
        throw failure
    }
}
