package dev.turjo.paperless.core.pdf

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShareIntentsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun singleFileUsesSendWithReadGrant() {
        val file = File(exportCacheDir(context), "page.pdf")
        val chooser = ShareIntents.forFiles(listOf(file), "application/pdf", ::testUri)
        val send = assertChooser(chooser, Intent.ACTION_SEND, "application/pdf")
        val uri = requireNotNull(send.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
        assertUri(file, uri)
    }

    @Test
    fun severalFilesUseSendMultipleWithReadGrant() {
        val dir = exportCacheDir(context)
        val files = listOf(File(dir, "page-1.jpg"), File(dir, "page-2.jpg"))
        val chooser = ShareIntents.forFiles(files, "image/jpeg", ::testUri)
        val send = assertChooser(chooser, Intent.ACTION_SEND_MULTIPLE, "image/jpeg")
        val uris = requireNotNull(send.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java))
        assertEquals(files.size, uris.size)
        files.zip(uris).forEach { (file, uri) -> assertUri(file, uri) }
    }

    @Test
    fun mergedManifestRegistersNonExportedProviderWithReadGrants() {
        val provider = requireNotNull(
            context.packageManager.resolveContentProvider("${context.packageName}.fileprovider", 0),
        )
        assertEquals(ExportFileProvider::class.java.name, provider.name)
        assertFalse(provider.exported)
        assertTrue(provider.grantUriPermissions)
    }

    // AndroidX FileProvider's root check uses Android's slash separator on the Windows JVM.
    private fun testUri(file: File): Uri =
        Uri.parse("content://${context.packageName}.fileprovider/exports").buildUpon()
            .appendPath(file.name)
            .build()

    private fun assertChooser(chooser: Intent, action: String, mime: String): Intent {
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val send = requireNotNull(chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java))
        assertEquals(action, send.action)
        assertEquals(mime, send.type)
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertTrue(chooser.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        return send
    }

    private fun assertUri(file: File, uri: Uri) {
        assertEquals("content", uri.scheme)
        assertEquals("${context.packageName}.fileprovider", uri.authority)
        assertEquals(file.name, uri.lastPathSegment)
    }
}
