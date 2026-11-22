package dev.turjo.paperless

import android.content.Intent
import dev.turjo.paperless.core.model.AppLock
import dev.turjo.paperless.core.model.PdfKey
import java.net.URI
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EntryRoutesTest {
    @Test
    fun viewingPdfUsesIntentDataForContentAndFileUris() = runBlocking {
        val repository = FakePdfRegistrar()
        for (source in listOf(URI("content://provider/manual.pdf"), URI("file:///sdcard/manual.pdf"))) {
            val events = pdfEntryEvents(
                Intent.ACTION_VIEW, PDF_MIME_TYPE, source, listOf(URI("content://ignored/other.pdf")),
                URI::getScheme, repository::registerExternal,
            )
            assertEquals(listOf(EntryEvent(pdfKey = PdfKey(source.toString()), pdfTitle = "manual.pdf")), events)
        }
        assertEquals(2, repository.registered.size)
    }

    @Test
    fun sharingPdfUsesStreamAndIgnoresAnUnrelatedDataUri() = runBlocking {
        val repository = FakePdfRegistrar()
        val stream = URI("content://provider/shared.pdf")
        val events = pdfEntryEvents(
            Intent.ACTION_SEND, PDF_MIME_TYPE, URI("content://ignored/other.pdf"), listOf(stream),
            URI::getScheme, repository::registerExternal,
        )
        assertEquals(listOf(EntryEvent(pdfKey = PdfKey(stream.toString()), pdfTitle = "shared.pdf")), events)
        assertEquals(listOf(stream), repository.registered)
    }

    @Test
    fun severalSharedPdfsUseEachSupportedStreamOnceInOrder() = runBlocking {
        val repository = FakePdfRegistrar()
        val first = URI("content://provider/first.pdf")
        val second = URI("file:///sdcard/second.pdf")
        val events = pdfEntryEvents(
            Intent.ACTION_SEND_MULTIPLE, PDF_MIME_TYPE, null,
            listOf(first, first, URI("https://provider/remote.pdf"), second), URI::getScheme,
            repository::registerExternal,
        )
        assertEquals(listOf(first, second), repository.registered)
        assertEquals(listOf(PdfKey(first.toString()), PdfKey(second.toString())), events.map { it.pdfKey })
    }

    @Test
    fun missingPdfStreamProducesAnErrorAndImagesStayOnTheirImportPath() = runBlocking {
        val repository = FakePdfRegistrar()
        val missing = pdfEntryEvents(
            Intent.ACTION_SEND, PDF_MIME_TYPE, null, emptyList(), URI::getScheme, repository::registerExternal,
        )
        assertEquals(listOf(EntryEvent(message = R.string.pdf_open_error)), missing)
        assertTrue(acceptsFileIntent(Intent.ACTION_SEND, "image/jpeg"))
        assertFalse(acceptsFileIntent(Intent.ACTION_VIEW, "image/jpeg"))
        assertEquals(emptyList<URI>(), repository.registered)
    }

    @Test
    fun consumptionOnlyRemovesTheMatchingPendingHead() {
        val first = PendingPdf(PdfKey("first"), "First")
        val second = PendingPdf(PdfKey("second"), "Second")
        val queued = listOf(first, second)
        assertEquals(queued, queued.consumePdf(null))
        assertEquals(queued, queued.consumePdf(second.key))
        val remaining = queued.consumePdf(first.key)
        assertEquals(listOf(second), remaining)
        assertEquals(remaining, remaining.consumePdf(first.key))
        assertEquals(emptyList<PendingPdf>(), remaining.consumePdf(second.key))
    }

    private class FakePdfRegistrar {
        val registered = mutableListOf<URI>()

        fun registerExternal(uri: URI): Pair<PdfKey, String> {
            registered += uri
            return PdfKey(uri.toString()) to uri.path.substringAfterLast('/')
        }
    }

    @Test
    fun shortcutsBuildLibraryAndDestinationStack() {
        assertEquals(listOf(LibraryRoute, CameraRoute()), startBackStack("camera_batch"))
        assertEquals(listOf(LibraryRoute, CameraRoute(single = true)), startBackStack("camera_single"))
        assertEquals(listOf(LibraryRoute, DocumentRoute("abc")), startBackStack("document:abc"))
    }

    @Test
    fun missingAndInvalidRoutesOpenLibrary() {
        for (value in listOf(null, "camera", "document:", "document: ")) {
            assertEquals(listOf(LibraryRoute), startBackStack(value))
        }
    }

    @Test
    fun coldStartLocksOnlyWhenEnabled() {
        assertFalse(shouldLock(AppLock.OFF, 5, null, 0L, true))
        assertTrue(shouldLock(AppLock.ON_OPEN, 5, null, 0L, true))
        assertTrue(shouldLock(AppLock.AFTER_MINUTES, 5, null, 0L, true))
    }

    @Test
    fun onOpenLocksOnReturnFromBackground() {
        assertFalse(shouldLock(AppLock.ON_OPEN, 5, null, 1L, false))
        assertTrue(shouldLock(AppLock.ON_OPEN, 5, 0L, 1L, false))
    }

    @Test
    fun timeoutLocksAtTheConfiguredThreshold() {
        assertFalse(shouldLock(AppLock.AFTER_MINUTES, 5, 1_000L, 300_999L, false))
        assertTrue(shouldLock(AppLock.AFTER_MINUTES, 5, 1_000L, 301_000L, false))
    }
}
