package dev.turjo.paperless.core.data

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.turjo.paperless.core.model.PdfKey
import dev.turjo.paperless.core.pdf.AndroidPdfSessionManager
import dev.turjo.paperless.core.pdf.MediaStoreSaver
import dev.turjo.paperless.core.pdf.PdfInfo
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

sealed interface SaveResult {
    data class Saved(val uri: Uri) : SaveResult
    data object NotEditable : SaveResult
    data class Failed(val cause: Exception, val backup: File? = null) : SaveResult
}

interface PdfSaveService {
    suspend fun replaceExternal(key: PdfKey, newFile: File): SaveResult
    suspend fun saveCopy(key: PdfKey, newFile: File): SaveResult
}

@Singleton
class SafPdfSaveService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: RoomReaderRepository,
    private val sessions: AndroidPdfSessionManager,
) : PdfSaveService {
    private val resolver = context.contentResolver
    private val saver = MediaStoreSaver(context)
    private val overwriteLock = Mutex()

    override suspend fun replaceExternal(key: PdfKey, newFile: File): SaveResult = withContext(Dispatchers.IO) {
        if (key.isLibraryDocument()) return@withContext SaveResult.NotEditable
        overwriteLock.withLock {
            var backup: File? = null
            var writeStarted = false
            try {
                val pages = PdfInfo.pageCount(context, newFile)
                val uri = repository.resolveUri(key)
                if (uri.scheme != "content") return@withLock SaveResult.NotEditable

                val directory = File(context.cacheDir, "reader/backup")
                if (!directory.mkdirs() && !directory.isDirectory) throw IOException("Cannot create PDF backup directory")
                val original = File.createTempFile("original-", ".pdf", directory)
                backup = original
                val input = resolver.openInputStream(uri) ?: throw IOException("Cannot read original PDF")
                input.use { source -> original.outputStream().use { source.copyTo(it) } }
                if (PdfInfo.pageCount(context, original) != pages) throw IOException("Replacement page count differs")
                ensureActive()

                // A provider can truncate as soon as the output stream opens.
                withContext(NonCancellable) {
                    try {
                        newFile.inputStream().use { source ->
                            val output = resolver.openOutputStream(uri, "wt")
                                ?: throw IOException("Cannot write PDF")
                            writeStarted = true
                            output.use { source.copyTo(it) }
                        }
                    } catch (error: Exception) {
                        if (writeStarted) {
                            try {
                                val output = resolver.openOutputStream(uri, "wt")
                                    ?: throw IOException("Cannot restore original PDF")
                                output.use { destination -> original.inputStream().use { it.copyTo(destination) } }
                            } catch (restoreError: Exception) {
                                error.addSuppressed(restoreError)
                            }
                        }
                        throw error
                    } finally {
                        if (writeStarted) sessions.invalidate(key)
                    }
                    original.delete()
                    backup = null
                    SaveResult.Saved(uri)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                SaveResult.Failed(error, backup.takeIf { writeStarted })
            } finally {
                if (!writeStarted) backup?.delete()
            }
        }
    }

    override suspend fun saveCopy(key: PdfKey, newFile: File): SaveResult = withContext(Dispatchers.IO) {
        try {
            PdfInfo.pageCount(context, newFile)
            val title = repository.readerTitle(key)?.substringBeforeLast('.')?.ifBlank { "Document" } ?: "Document"
            val filename = title.replace(Regex("""[/\\\p{Cntrl}]"""), "_") + " (edited).pdf"
            SaveResult.Saved(saver.savePdf(newFile, filename))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            SaveResult.Failed(error)
        }
    }
}
