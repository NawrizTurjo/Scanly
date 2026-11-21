package dev.turjo.paperless.core.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RoomRepositoriesTest {
    private lateinit var database: PaperlessDatabase
    private lateinit var dao: DocumentDao
    private lateinit var files: FileStore
    private lateinit var documents: DocumentRepository
    private lateinit var sessions: ScanSessionRepository
    private lateinit var jpeg: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, PaperlessDatabase::class.java).build()
        dao = database.documentDao()
        files = FileStore(context)
        documents = RoomDocumentRepository(database, dao, files, ThumbnailMaker(files))
        sessions = RoomScanSessionRepository(database, dao, database.scanSessionDao(), files)
        jpeg = files.newTempFile(".jpg")
        val bitmap = Bitmap.createBitmap(960, 480, Bitmap.Config.ARGB_8888)
        try {
            jpeg.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
        } finally {
            bitmap.recycle()
        }
    }

    @After
    fun tearDown() {
        jpeg.delete()
        database.close()
    }

    @Test
    fun appendAndInsertKeepIndicesContiguous() = runBlocking {
        val id = draft()
        val first = add(id)
        val last = add(id)
        val middle = add(id, 1)

        assertOrder(id, first.id, middle.id, last.id)
        assertTrue(jpeg.isFile)
        assertEquals(jpeg.readBytes().toList(), File(middle.originalPath).readBytes().toList())
    }

    @Test
    fun concurrentAppendsKeepIndicesContiguous() = runBlocking {
        val id = draft()
        val first = async { add(id) }
        val second = async { add(id) }
        val ids = setOf(first.await().id, second.await().id)
        val pages = dao.pages(id)

        assertEquals(ids, pages.map(PageEntity::id).toSet())
        assertEquals(listOf(0, 1), pages.map(PageEntity::index))
    }

    @Test
    fun invalidInsertKeepsRowsAndFilesConsistent() = runBlocking {
        val id = draft()
        val first = add(id)
        val directories = files.pageDir(first.id).parentFile!!.list()!!.toSet()

        val failure = runCatching { add(id, 2) }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertEquals(directories, files.pageDir(first.id).parentFile!!.list()!!.toSet())
        assertOrder(id, first.id)
    }

    @Test
    fun reorderAndDeleteCompactIndicesAndDeleteFiles() = runBlocking {
        val id = draft()
        val first = add(id)
        val second = add(id)
        val third = add(id)
        val cover = dao.document(id)!!
        dao.updateDocument(cover.copy(coverPageId = first.id))
        files.processedFile(first.id).writeText("cache")

        documents.reorderPages(id, listOf(third.id, first.id, second.id))
        assertOrder(id, third.id, first.id, second.id)
        documents.deletePage(first.id)

        assertOrder(id, third.id, second.id)
        assertNull(dao.document(id)?.coverPageId)
        assertFalse(files.pageDir(first.id).exists())
        assertTrue(files.originalFile(second.id).isFile)
    }

    @Test
    fun rotationWrapsAndUpdateBumpsDocumentTime() = runBlocking {
        val id = draft()
        val page = add(id)
        dao.updateDocument(dao.document(id)!!.copy(updatedAt = 1))
        documents.updatePage(page.copy(rotation = 270))
        assertTrue(dao.document(id)!!.updatedAt > 1)

        documents.rotatePage(page.id, 90)
        assertEquals(0, documents.getPage(page.id)?.rotation)
        documents.rotatePage(page.id, -90)
        assertEquals(270, documents.getPage(page.id)?.rotation)
    }

    @Test
    fun libraryFiltersSortsAndFindsCover() = runBlocking {
        val older = saved("Older")
        dao.updateDocument(dao.document(older)!!.copy(updatedAt = 1))
        val newer = saved("Newer")
        val first = add(newer)
        val cover = add(newer)
        dao.updateDocument(dao.document(newer)!!.copy(coverPageId = cover.id, isFavorite = true))
        val deleted = saved("Deleted")
        documents.softDelete(deleted)
        draft()

        val library = documents.observeLibrary().first()
        assertEquals(listOf(newer, older), library.map { it.id })
        assertEquals(2, library.first().pageCount)
        assertTrue(library.first().isFavorite)
        assertEquals(files.thumbFile(cover.id).path, library.first().coverThumbPath)
        assertNull(library.last().coverThumbPath)

        dao.updateDocument(dao.document(newer)!!.copy(coverPageId = null))
        assertEquals(files.thumbFile(first.id).path, documents.observeLibrary().first().first().coverThumbPath)
        documents.restore(deleted)
        assertTrue(documents.observeLibrary().first().any { it.id == deleted })
    }

    @Test
    fun commitAsNewRevealsDraftAndFinishClearsSession() = runBlocking {
        val session = sessions.start()
        val id = session.draftDocumentId
        val page = add(id)

        assertEquals(id, documents.commitDraft(id, "Receipt"))
        sessions.finish(session.id)

        assertEquals("Receipt", documents.observeLibrary().first().single().title)
        assertFalse(documents.observeDocument(id).first()!!.document.isDraft)
        assertOrder(id, page.id)
        assertTrue(files.originalFile(page.id).isFile)
        assertNull(sessions.active())
    }

    @Test
    fun commitIntoTargetKeepsPageFilesAndDeletesDraft() = runBlocking {
        val target = saved("Target")
        val first = add(target)
        val second = add(target)
        val third = add(target)
        val session = sessions.start(target, 2)
        val draft = session.draftDocumentId
        val inserted = add(draft)
        val alsoInserted = add(draft)
        val path = files.originalFile(inserted.id)
        val thumbnail = files.thumbFile(inserted.id)
        val originalBytes = path.readBytes()
        val thumbBytes = thumbnail.readBytes()

        assertEquals(target, documents.commitDraft(draft, "Unused", target, 2))
        sessions.finish(session.id)

        assertOrder(target, first.id, second.id, inserted.id, alsoInserted.id, third.id)
        assertNull(documents.observeDocument(draft).first())
        assertEquals("Target", dao.document(target)?.title)
        assertEquals(path.path, documents.getPage(inserted.id)?.originalPath)
        assertTrue(originalBytes.contentEquals(path.readBytes()))
        assertTrue(thumbBytes.contentEquals(thumbnail.readBytes()))
        assertNull(sessions.active())
    }

    @Test
    fun onlyOneSessionAndDiscardRemovesDraftAndFiles() = runBlocking {
        val session = sessions.start()
        val page = add(session.draftDocumentId)

        assertEquals(session, sessions.observeActive().first())
        assertTrue(runCatching { sessions.start() }.exceptionOrNull() is IllegalStateException)
        sessions.discard(session.id)

        assertNull(sessions.active())
        assertNull(dao.document(session.draftDocumentId))
        assertNull(documents.getPage(page.id))
        assertFalse(files.pageDir(page.id).exists())
    }

    @Test
    fun thumbnailAppliesExifAndLimitsLongSide() = runBlocking {
        withContext(Dispatchers.IO) {
            ExifInterface(jpeg.path).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
                saveAttributes()
            }
        }
        val page = add(draft())
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(files.thumbFile(page.id).path, bounds)

        assertEquals(240, bounds.outWidth)
        assertEquals(480, bounds.outHeight)
        assertEquals("image/webp", bounds.outMimeType)
    }

    private suspend fun draft() = sessions.start().draftDocumentId

    private suspend fun saved(title: String): String {
        val session = sessions.start()
        return documents.commitDraft(session.draftDocumentId, title).also { sessions.finish(session.id) }
    }

    private suspend fun add(id: String, at: Int? = null) = documents.addPage(id, jpeg, 960, 480, at)

    private suspend fun assertOrder(id: String, vararg ids: String) {
        val pages = documents.observeDocument(id).first()!!.pages
        assertEquals(ids.toList(), pages.map { it.id })
        assertEquals(ids.indices.toList(), pages.map { it.index })
    }
}
