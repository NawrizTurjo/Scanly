package dev.turjo.paperless

import android.content.ContentResolver
import android.graphics.BitmapFactory
import android.net.Uri
import dev.turjo.paperless.core.data.DocumentRepository
import dev.turjo.paperless.core.data.FileStore
import dev.turjo.paperless.core.data.PageProcessing
import dev.turjo.paperless.core.data.ScanSessionRepository
import dev.turjo.paperless.core.model.ScanSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal data class SharedImageResult(val draftId: String?, val hadErrors: Boolean)

internal suspend fun importSharedImages(
    uris: List<Uri>,
    resolver: ContentResolver,
    files: FileStore,
    documents: DocumentRepository,
    sessions: ScanSessionRepository,
    processing: PageProcessing,
): SharedImageResult = withContext(Dispatchers.IO) {
    var target: ScanSession? = null
    var draftId: String? = null
    var hadErrors = false
    for (uri in uris) {
        try {
            withContext(NonCancellable) {
                val temp = files.newTempFile(".jpg")
                try {
                    checkNotNull(resolver.openInputStream(uri)).use { input ->
                        temp.outputStream().use { input.copyTo(it) }
                    }
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(temp.path, bounds)
                    require(bounds.outWidth > 0 && bounds.outHeight > 0)
                    val session = target ?: sessions.active() ?: try {
                        sessions.start()
                    } catch (error: IllegalStateException) {
                        sessions.active() ?: throw error
                    }
                    target = session
                    val page = documents.addPage(session.draftDocumentId, temp, bounds.outWidth, bounds.outHeight)
                    processing.enqueueNewPage(page.id)
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
    SharedImageResult(draftId, hadErrors || uris.isEmpty())
}
