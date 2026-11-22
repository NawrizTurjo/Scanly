package dev.turjo.paperless.core.pdf

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.MediaStore.MediaColumns
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class MediaStoreSaver(context: Context) {
    private val resolver = context.applicationContext.contentResolver

    suspend fun savePdf(src: File, displayName: String): Uri = save(
        src,
        MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
        pendingExportValues(displayName, "application/pdf", Environment.DIRECTORY_DOCUMENTS),
    )

    suspend fun saveImage(src: File, displayName: String, mime: String): Uri = save(
        src,
        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
        pendingExportValues(displayName, mime, Environment.DIRECTORY_PICTURES),
    )

    private suspend fun save(src: File, collection: Uri, values: ContentValues): Uri =
        withContext(Dispatchers.IO) {
            val uri = resolver.insert(collection, values)
                ?: throw IOException("Cannot create MediaStore export")
            try {
                val output = resolver.openOutputStream(uri, "w")
                    ?: throw IOException("Cannot open MediaStore export")
                output.use { dst -> src.inputStream().use { it.copyTo(dst) } }
                ensureActive()

                val published = ContentValues().apply { put(MediaColumns.IS_PENDING, 0) }
                if (resolver.update(uri, published, null, null) != 1) {
                    throw IOException("Cannot publish MediaStore export")
                }
                uri
            } catch (failure: Exception) {
                try {
                    resolver.delete(uri, null, null)
                } catch (cleanupFailure: Exception) {
                    failure.addSuppressed(cleanupFailure)
                }
                throw failure
            }
        }
}

internal fun pendingExportValues(displayName: String, mime: String, directory: String): ContentValues =
    ContentValues().apply {
        put(MediaColumns.DISPLAY_NAME, displayName)
        put(MediaColumns.MIME_TYPE, mime)
        put(MediaColumns.RELATIVE_PATH, "$directory/Scanly/")
        put(MediaColumns.IS_PENDING, 1)
    }
