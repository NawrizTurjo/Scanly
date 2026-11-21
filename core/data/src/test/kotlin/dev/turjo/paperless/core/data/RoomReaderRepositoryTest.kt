package dev.turjo.paperless.core.data

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.turjo.paperless.core.model.PdfKey
import dev.turjo.paperless.core.model.ReaderViewMode
import dev.turjo.paperless.core.model.TabState
import java.io.File
import java.io.IOException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RoomReaderRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, PaperlessDatabase::class.java).build()
    private var exports = 0
    private var failExport = false
    private val repository = RoomReaderRepository(db, context) { document, file ->
        exports++
        file.writeText(document.title)
        if (failExport) throw IOException("Export failed")
        document.updatedAt
    }

    @After
    fun tearDown() {
        db.close()
        File(context.cacheDir, "pdf").deleteRecursively()
    }

    @Test
    fun savesRestoresAndClearsTabs() = runBlocking {
        val first = TabState("t1", PdfKey("doc:d1"), "Scan", 4, 2f, 120, ReaderViewMode.SINGLE_PAGE, true)
        val second = TabState("t2", PdfKey("e1"), "External")
        val tabs = listOf(second, first)
        repository.saveTabs(tabs, first.tabId)
        assertEquals(tabs, repository.observeTabs().first())
        assertEquals(first.tabId, repository.observeActiveTabId().first())
        repository.saveTabs(emptyList(), null)
        assertEquals(emptyList<TabState>(), repository.observeTabs().first())
        assertNull(repository.observeActiveTabId().first())
    }

    @Test
    fun recentsCombineDocumentsAndExternalPdfsWithSingleLimit() = runBlocking {
        repeat(22) { index ->
            val id = "external-$index"
            db.readerDao().insertExternal(ExternalPdfEntity(id, "content://pdf/$index", id, persistedPermission = false))
            db.readerDao().markExternalOpened(id, 10L + index, 10, index % 10)
        }
        val scan = document("scan").copy(lastOpenedAt = 100, pageCount = 4, lastPage = 2)
        db.documentDao().insertDocument(scan)
        db.documentDao().insertDocument(scan.copy(id = "deleted", deletedAt = 100))
        db.documentDao().insertDocument(scan.copy(id = "unopened", lastOpenedAt = 0))
        val rows = repository.observeRecent().first()
        assertEquals(listOf("doc:scan") + (21 downTo 3).map { "external-$it" }, rows.map { it.pdfKey.value })
        assertEquals(4, rows.first().pageCount)
        assertEquals(2, rows.first().lastPage)
    }

    @Test
    fun externalRegistrationReusesKeysAndUpdatesReadingProgress() = runBlocking {
        val uri = Uri.parse("content://pdf/sample")
        val key = repository.registerExternal(uri, "Original")
        repository.markOpened(key, 15, 4)
        assertEquals(key, repository.registerExternal(uri, "Renamed"))
        assertEquals(uri, repository.resolveUri(key))
        val recent = repository.observeRecent().first().single()
        assertEquals("Renamed", recent.title)
        assertEquals(15, recent.pageCount)
        assertEquals(4, recent.lastPage)
    }

    @Test
    fun exportsOnlyWhenStaleAndPreservesPreviousPdfOnFailure() = runBlocking {
        val scan = document("scan")
        db.documentDao().insertDocument(scan)
        val key = PdfKey("doc:scan")
        assertEquals(listOf(key, key), List(2) { async { repository.pdfKeyForDocument(scan.id) } }.awaitAll())
        val file = File(requireNotNull(repository.resolveUri(key).path))
        assertEquals(1, exports)
        assertEquals("Scan", file.readText())
        repository.markOpened(key, 3, 1)
        repository.resolveUri(key)
        assertEquals(1, exports)

        val current = requireNotNull(db.documentDao().document(scan.id))
        db.documentDao().updateDocument(current.copy(title = "Edited", updatedAt = file.lastModified() + 1000))
        failExport = true
        assertTrue(runCatching { repository.resolveUri(key) }.exceptionOrNull() is IOException)
        assertEquals("Scan", file.readText())

        failExport = false
        assertEquals(key, repository.pdfKeyForDocument(scan.id))
        assertEquals("Edited", file.readText())
        assertEquals(3, exports)
    }

    private fun document(id: String) = DocumentEntity(
        id, "Scan", null, false, false, 1000, 1000, null, null,
    )
}
