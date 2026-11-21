package dev.turjo.paperless.core.pdf

import android.os.Environment
import android.provider.MediaStore
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaStoreSaverTest {
    @Test
    fun pdfEntryIsPendingInDocumentsPaperless() {
        assertPendingValues("scan.pdf", "application/pdf", Environment.DIRECTORY_DOCUMENTS)
    }

    @Test
    fun pngEntryIsPendingInPicturesPaperless() {
        assertPendingValues("scan.png", "image/png", Environment.DIRECTORY_PICTURES)
    }

    private fun assertPendingValues(name: String, mime: String, directory: String) {
        val values = pendingExportValues(name, mime, directory)
        assertEquals(name, values.getAsString(MediaStore.MediaColumns.DISPLAY_NAME))
        assertEquals(mime, values.getAsString(MediaStore.MediaColumns.MIME_TYPE))
        assertEquals("$directory/Paperless", values.getAsString(MediaStore.MediaColumns.RELATIVE_PATH).trimEnd('/'))
        assertEquals(1, values.getAsInteger(MediaStore.MediaColumns.IS_PENDING).toInt())
    }
}
