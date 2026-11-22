package dev.turjo.paperless.core.data

import android.content.Context
import android.graphics.BitmapFactory
import android.os.Debug
import android.util.Log
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.turjo.paperless.core.model.Page
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScanRobustnessTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val assets = InstrumentationRegistry.getInstrumentation().context.assets
    private val files = FileStore(context)
    private val thumbnails = ThumbnailMaker(files)

    @Test
    fun processesFiftyQueuedPagesWithBoundedNativeHeap() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, PaperlessDatabase::class.java).build()
        val sessions = sessions(database)
        val documents = documents(database)
        val parent = SupervisorJob()
        val active = mutableSetOf<String>()
        var maxActive = 0
        val tracked = object : DocumentRepository by documents {
            override suspend fun getPage(id: String): Page? {
                val started = synchronized(active) {
                    active.add(id).also { maxActive = maxOf(maxActive, active.size) }
                }
                if (started) delay(1)
                return documents.getPage(id)
            }

            override suspend fun updatePage(page: Page) {
                documents.updatePage(page)
                if (page.processedHash != null) synchronized(active) { active.remove(page.id) }
            }
        }
        val queue: PageProcessing = QueuedPageProcessing(tracked, files, thumbnails, CoroutineScope(parent))
        val fixtures = listOf("clean_01.jpg", "skewed_04.jpg").map(::fixture)
        val session = sessions.start()
        try {
            val pages = List(50) { index -> addPage(documents, session.draftDocumentId, fixtures[index % fixtures.size]) }
            val baseline = Debug.getNativeHeapAllocatedSize()
            val peak = AtomicLong(baseline)
            val sampler = launch(Dispatchers.Default) {
                while (isActive) {
                    peak.accumulateAndGet(Debug.getNativeHeapAllocatedSize(), ::maxOf)
                    delay(5)
                }
            }
            try {
                pages.forEach { queue.enqueueNewPage(it.id) }
                withTimeout(300_000) { queue.awaitIdle(pages.map(Page::id)) }
            } finally {
                sampler.cancelAndJoin()
            }
            val growth = peak.get() - baseline
            Log.i("P8Robustness", "50 pages: sampled native heap growth=$growth bytes, peak active pages=$maxActive")
            assertEquals(1, maxActive)
            assertTrue("Native heap grew by $growth bytes", growth < 400_000_000L)
            for (page in pages) {
                val processed = documents.getPage(page.id)
                assertTrue("Missing output for ${page.id}", processed?.processedPath?.let { File(it).isFile } == true)
                assertTrue("Missing processed hash for ${page.id}", processed?.processedHash != null)
            }
        } finally {
            parent.cancelAndJoin()
            sessions.discard(session.id)
            database.close()
            fixtures.forEach(File::delete)
        }
    }

    @Test
    fun draftAndPagesSurviveClosingAndReopeningDatabase() = runBlocking {
        val name = "scan-survival-${UUID.randomUUID()}.db"
        val original = fixture("clean_01.jpg")
        val added = mutableListOf<Page>()
        try {
            val initial = openDatabase(name)
            val session = try {
                val database = initial
                sessions(database).start().also { session ->
                    repeat(2) { added += addPage(documents(database), session.draftDocumentId, original) }
                }
            } finally {
                initial.close()
            }
            val reopened = openDatabase(name)
            try {
                val database = reopened
                val restoredSessions = sessions(database)
                assertEquals(session, restoredSessions.active())
                assertEquals(true, database.documentDao().document(session.draftDocumentId)?.isDraft)
                val restored = database.documentDao().pages(session.draftDocumentId)
                assertEquals(added.map(Page::id), restored.map { it.id })
                assertEquals(listOf(0, 1), restored.map { it.index })
                restored.forEach { assertTrue(File(it.originalPath).isFile) }
                restoredSessions.discard(session.id)
            } finally {
                reopened.close()
            }
        } finally {
            added.forEach { files.deletePageFiles(it.id) }
            context.deleteDatabase(name)
            original.delete()
        }
        Unit
    }

    private fun openDatabase(name: String) = Room.databaseBuilder(context, PaperlessDatabase::class.java, name).build()

    private fun sessions(database: PaperlessDatabase) =
        RoomScanSessionRepository(database, database.documentDao(), database.scanSessionDao(), files)

    private fun documents(database: PaperlessDatabase) =
        RoomDocumentRepository(database, database.documentDao(), files, thumbnails)

    private fun fixture(name: String) = files.newTempFile(".jpg").apply {
        assets.open(name).use { input -> outputStream().use { input.copyTo(it) } }
    }

    private suspend fun addPage(documents: DocumentRepository, draftId: String, original: File): Page {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(original.path, bounds)
        return documents.addPage(draftId, original, bounds.outWidth, bounds.outHeight)
    }
}
