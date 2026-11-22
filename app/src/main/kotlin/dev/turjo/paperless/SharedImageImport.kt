package dev.turjo.paperless

import android.content.ContentResolver
import android.graphics.BitmapFactory
import android.net.Uri
import dev.turjo.paperless.core.data.DocumentRepository
import dev.turjo.paperless.core.data.FileStore
import dev.turjo.paperless.core.data.PageProcessing
import dev.turjo.paperless.core.data.PixelSize
import dev.turjo.paperless.core.data.ScanSessionRepository
import dev.turjo.paperless.core.model.ScanSession
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class SharedImageResult(
    val draftId: String?,
    val hadErrors: Boolean,
    val previousScanSaved: Boolean,
)

private val imageImportMutex = Mutex()

internal suspend fun importSharedImages(
    uris: List<Uri>,
    resolver: ContentResolver,
    files: FileStore,
    documents: DocumentRepository,
    sessions: ScanSessionRepository,
    processing: PageProcessing,
): SharedImageResult = imageImportMutex.withLock {
    importImageBatch(
        images = uris,
        sessions = sessions,
        newTempFile = { files.newTempFile(".jpg") },
        readImage = { uri, temp ->
            checkNotNull(resolver.openInputStream(uri)).use { input ->
                temp.outputStream().use { input.copyTo(it) }
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(temp.path, bounds)
            PixelSize(bounds.outWidth, bounds.outHeight)
        },
        addPage = { draftId, temp, size ->
            val page = documents.addDraftPage(draftId, temp, size.width, size.height)
            processing.enqueueNewPage(page.id)
        },
    )
}

internal suspend fun <T> importImageBatch(
    images: List<T>,
    sessions: ScanSessionRepository,
    newTempFile: () -> File,
    readImage: (T, File) -> PixelSize,
    addPage: suspend (String, File, PixelSize) -> Unit,
): SharedImageResult = withContext(Dispatchers.IO) {
    var target: ScanSession? = null
    var draftId: String? = null
    var hadErrors = false
    var previousScanSaved = false
    for (image in images) {
        try {
            withContext(NonCancellable) {
                val temp = newTempFile()
                try {
                    val size = readImage(image, temp)
                    require(size.width > 0 && size.height > 0)
                    val session = target ?: sessions.startFresh(saveExisting = true).let { fresh ->
                        previousScanSaved = fresh.savedTitle != null
                        target = fresh.session
                        fresh.session
                    }
                    addPage(session.draftDocumentId, temp, size)
                    draftId = session.draftDocumentId
                } finally {
                    temp.delete()
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            hadErrors = true
        }
    }
    SharedImageResult(draftId, hadErrors || images.isEmpty(), previousScanSaved)
}
