package dev.turjo.paperless.core.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.turjo.paperless.core.model.LibraryFilter
import dev.turjo.paperless.core.model.LibrarySort
import dev.turjo.paperless.core.model.PageFilter
import dev.turjo.paperless.core.model.PageMode
import dev.turjo.paperless.core.model.Quad
import dev.turjo.paperless.core.model.UserSettings
import dev.turjo.paperless.core.model.paramsHash
import java.io.File
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
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
        sessions = RoomScanSessionRepository(
            database, dao, database.scanSessionDao(), files, documents,
            flowOf(UserSettings(autoNamePattern = "Filed {date}")),
        )
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
    fun tagsSupportCrudFilteringSearchAndCascades() = runBlocking {
        val id = "notes"
        dao.insertDocument(DocumentEntity(id, "Lecture notes", null, false, false, 1, 1, null, null))
        dao.insertDocument(DocumentEntity("other", "Other", null, false, false, 1, 1, null, null))
        val physics = documents.createTag(" Physics ")
        val optics = documents.createTag("Optics")
        assertEquals(listOf(1, 0), documents.observeTags().first().map { it.tag.colorIndex })
        assertTrue(runCatching { documents.createTag("pHySiCs") }.isFailure)
        assertTrue(runCatching { documents.renameTag(optics, "pHySiCs") }.isFailure)
        for (invalid in listOf("  ", "a".repeat(31))) {
            assertTrue(runCatching { documents.createTag(invalid) }.isFailure)
        }
        documents.setDocumentTags(id, setOf(physics), newName = "Homework")
        assertEquals(listOf("Homework", "Physics"), documents.observeDocumentTags(id).first().map { it.name })
        assertEquals(listOf(id), documents.observeLibrary(LibraryFilter(tagId = physics)).first().map { it.id })
        assertEquals(listOf(id), documents.observeLibrary(LibraryFilter(query = "physics")).first().map { it.id })
        assertTrue(runCatching { documents.setDocumentTags(id, setOf("invalid"), "Rollback") }.isFailure)
        assertEquals(listOf("Homework", "Physics"), documents.observeDocumentTags(id).first().map { it.name })
        assertFalse(documents.observeTags().first().any { it.tag.name == "Rollback" })
        documents.renameTag(physics, " Mechanics ")
        assertEquals(listOf("Homework", "Mechanics"), documents.observeDocumentTags(id).first().map { it.name })
        assertEquals(1, documents.observeTags().first().single { it.tag.id == physics }.documentCount)
        documents.deleteTag(physics)
        assertEquals(listOf("Homework"), documents.observeDocumentTags(id).first().map { it.name })
        documents.softDelete(id)
        assertEquals(0, documents.observeTags().first().single { it.tag.name == "Homework" }.documentCount)
        assertEquals(1, database.tagDao().links().size)
        documents.restore(id)
        assertEquals(listOf("Homework"), documents.observeDocumentTags(id).first().map { it.name })
        documents.softDelete(id)
        documents.deleteForever(id)
        assertTrue(database.tagDao().links().isEmpty())
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
    fun bookSplitInsertsRightHalfWithIndependentOriginalAndIsSafeToRetry() = runBlocking {
        val id = draft()
        val spread = add(id)
        val tail = add(id)
        val left = spread.copy(mode = PageMode.BOOK_L, quad = Quad(0f, 0f, 480f, 0f, 480f, 479f, 0f, 479f))
        val rightQuad = Quad(480f, 0f, 959f, 0f, 959f, 479f, 480f, 479f)

        val right = documents.splitBookPage(left, rightQuad)
        assertEquals(right, documents.splitBookPage(left, rightQuad))
        assertOrder(id, spread.id, right.id, tail.id)
        assertEquals(left, documents.getPage(spread.id))
        assertEquals(PageMode.BOOK_R, right.mode)
        assertEquals(rightQuad, right.quad)
        assertTrue(right.originalPath != left.originalPath)
        assertTrue(File(left.originalPath).readBytes().contentEquals(File(right.originalPath).readBytes()))
    }

    @Test
    fun idCompositeIsInsertedAfterTheBackAsAnOriginalPage() = runBlocking {
        val id = draft()
        val front = add(id).copy(mode = PageMode.ID_FRONT)
        val back = add(id).copy(mode = PageMode.ID_BACK, glareFix = true)
        documents.updatePage(front)
        documents.updatePage(back)
        val tail = add(id)

        val composite = documents.addIdComposite(back, jpeg, 960, 480)
        assertEquals(composite, documents.addIdComposite(back, jpeg, 960, 480))
        assertOrder(id, front.id, back.id, composite.id, tail.id)
        assertEquals(PageMode.NORMAL, composite.mode)
        assertEquals(PageFilter.ORIGINAL, composite.filter)
        assertNull(composite.quad)
        assertFalse(composite.deskew)
        assertFalse(composite.glareFix)
        assertEquals(composite.originalPath, composite.processedPath)
        assertEquals(composite.paramsHash(), composite.processedHash)
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
    fun foldersCountMovedDocumentsAndDeleteMovesDocumentsToRoot() = runBlocking {
        val folders = RoomFolderRepository(database)
        val parent = folders.create("  Receipts  ")
        val child = folders.create("Travel", parent)
        assertTrue(runCatching { folders.create("Too deep", child) }.exceptionOrNull() is IllegalArgumentException)

        val first = saved("Hotel")
        val second = saved("Train")
        val deleted = saved("Parking")
        folders.moveDocuments(setOf(first, second, deleted), child)
        documents.softDelete(deleted)
        folders.moveDocuments(setOf(first), parent)
        folders.rename(child, "Trips")
        val counts = folders.observeFolders().first().associateBy { it.folder.id }
        assertEquals("Receipts", counts.getValue(parent).folder.name)
        assertEquals("Trips", counts.getValue(child).folder.name)
        assertEquals(parent, counts.getValue(child).folder.parentId)
        assertEquals(1, counts.getValue(parent).documentCount)
        assertEquals(1, counts.getValue(child).documentCount)

        folders.moveDocuments(setOf(first), null)
        assertNull(dao.document(first)?.folderId)
        folders.delete(parent)
        assertNull(folders.observeFolders().first().single().folder.parentId)
        folders.delete(child)
        assertTrue(folders.observeFolders().first().isEmpty())
        assertNull(dao.document(second)?.folderId)
        assertNull(dao.document(deleted)?.folderId)
    }

    @Test
    fun libraryCombinesFolderFavouriteSearchAndSort() = runBlocking {
        val folders = RoomFolderRepository(database)
        val travel = folders.create("Travel Receipts")
        val other = folders.create("Other")
        val first = saved("Airport Taxi")
        val second = saved("Zebra Hotel")
        val third = saved("Museum")
        val unfavourited = saved("Bus")
        val elsewhere = saved("Travel Pass")
        folders.moveDocuments(setOf(first, second, third, unfavourited), travel)
        folders.moveDocuments(setOf(elsewhere), other)
        listOf(first, second, third, elsewhere).forEach { documents.setFavorite(it, true) }
        val zone = ZoneId.systemDefault()
        val firstDate = LocalDate.of(2025, 11, 17).atStartOfDay(zone).toInstant().toEpochMilli()
        val secondDate = LocalDate.of(2025, 11, 18).atStartOfDay(zone).toInstant().toEpochMilli()
        val thirdDate = LocalDate.of(2025, 11, 19).atStartOfDay(zone).toInstant().toEpochMilli()
        dao.updateDocument(dao.document(first)!!.copy(createdAt = firstDate, updatedAt = secondDate))
        dao.updateDocument(dao.document(second)!!.copy(createdAt = secondDate, updatedAt = firstDate))
        dao.updateDocument(dao.document(third)!!.copy(createdAt = thirdDate, updatedAt = thirdDate))

        val filter = LibraryFilter(folderId = travel, favouritesOnly = true, query = "rEcEiPtS November")
        suspend fun ids(filter: LibraryFilter) = documents.observeLibrary(filter).first().map { it.id }

        assertEquals(listOf(third, first, second), ids(filter))
        assertEquals(listOf(first, third, second), ids(filter.copy(sort = LibrarySort.NAME)))
        assertEquals(listOf(third, second, first), ids(filter.copy(sort = LibrarySort.CREATED_DESC)))
        assertEquals(listOf(second), ids(filter.copy(query = "hOtEl 2025-11-18")))
        assertEquals(emptyList<String>(), ids(filter.copy(query = "hotel 2025-11-17")))
        assertEquals(setOf(first, second, third, elsewhere), ids(filter.copy(folderId = null, query = "Travel")).toSet())
    }

    @Test
    fun duplicateRemapsPageIdsCoverAndFilePaths() = runBlocking {
        val source = saved("Receipt")
        val first = add(source)
        val second = add(source)
        val processed = files.processedFile(second.id).apply { writeText("processed content") }
        documents.updatePage(second.copy(processedPath = processed.path, processedHash = "processed hash"))
        dao.updateDocument(dao.document(source)!!.copy(coverPageId = second.id))

        val duplicate = documents.duplicate(source)
        val copy = documents.observeDocument(duplicate).first()!!
        assertTrue(duplicate != source)
        assertEquals("Copy of Receipt", copy.document.title)
        assertEquals(listOf(0, 1), copy.pages.map { it.index })
        assertEquals(copy.pages[1].id, copy.document.coverPageId)
        listOf(first, second).zip(copy.pages).forEach { (original, copied) ->
            assertTrue(original.id != copied.id)
            assertEquals(duplicate, copied.documentId)
            assertEquals(files.originalFile(copied.id).path, copied.originalPath)
            assertTrue(File(original.originalPath).readBytes().contentEquals(File(copied.originalPath).readBytes()))
            assertTrue(files.thumbFile(original.id).readBytes().contentEquals(files.thumbFile(copied.id).readBytes()))
        }
        assertEquals(files.processedFile(copy.pages[1].id).path, copy.pages[1].processedPath)
        assertEquals("processed content", files.processedFile(copy.pages[1].id).readText())
        assertEquals("processed hash", copy.pages[1].processedHash)
    }

    @Test
    fun failedDuplicateRollsBackRowsAndCopiedFiles() = runBlocking {
        val source = saved("Receipt")
        val first = add(source)
        val second = add(source)
        val pagesDirectory = files.pageDir(first.id).parentFile!!
        val originalDirectories = pagesDirectory.list()!!.toSet()
        assertTrue(files.originalFile(second.id).delete())

        assertTrue(runCatching { documents.duplicate(source) }.isFailure)

        assertEquals(listOf(source), documents.observeLibrary().first().map { it.id })
        assertEquals(originalDirectories, pagesDirectory.list()!!.toSet())
        assertOrder(source, first.id, second.id)
        assertTrue(files.originalFile(first.id).isFile)
    }

    @Test
    fun recycleBinRestoresRecentDocumentsAndPurgesOnlyExpiredDeletedDocuments() = runBlocking {
        val expired = saved("Expired")
        val recent = saved("Recent")
        val live = saved("Live")
        val expiredPage = add(expired)
        val recentPage = add(recent)
        val livePage = add(live)
        documents.softDelete(expired)
        documents.softDelete(recent)
        val now = System.currentTimeMillis()
        dao.updateDocument(dao.document(expired)!!.copy(deletedAt = now - Duration.ofDays(31).toMillis()))
        dao.updateDocument(dao.document(recent)!!.copy(deletedAt = now - Duration.ofDays(29).toMillis()))
        dao.updateDocument(dao.document(live)!!.copy(createdAt = now - Duration.ofDays(35).toMillis()))

        assertEquals(listOf(live), documents.observeLibrary().first().map { it.id })
        assertEquals(listOf(recent, expired), documents.observeDeleted().first().map { it.id })
        documents.purgeDeletedOlderThan(30)
        assertEquals(listOf(recent), documents.observeDeleted().first().map { it.id })
        assertNull(dao.document(expired))
        assertNull(dao.page(expiredPage.id))
        assertFalse(files.pageDir(expiredPage.id).exists())
        assertTrue(files.originalFile(recentPage.id).isFile)
        assertTrue(files.originalFile(livePage.id).isFile)

        documents.restore(recent)
        assertTrue(documents.observeDeleted().first().isEmpty())
        assertEquals(setOf(live, recent), documents.observeLibrary().first().map { it.id }.toSet())
        assertTrue(files.originalFile(recentPage.id).isFile)
    }

    @Test
    fun deleteForeverRemovesRowsAndAllFilesForEachPage() = runBlocking {
        val deleted = saved("Delete me")
        val pages = listOf(add(deleted), add(deleted))
        pages.forEach { files.processedFile(it.id).writeText("cache") }
        documents.softDelete(deleted)

        documents.deleteForever(deleted)

        assertNull(dao.document(deleted))
        assertTrue(documents.observeDeleted().first().isEmpty())
        pages.forEach { page ->
            assertNull(dao.page(page.id))
            assertFalse(files.pageDir(page.id).exists())
        }
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

    @Test
    fun startFreshSavesPreviousDraftAsAStandaloneDocument() = runBlocking {
        val target = saved("Target")
        val previous = sessions.start(target, 0)
        val page = add(previous.draftDocumentId)
        val progress = files.duplexFile(previous.id)
        progress.finishWrite(progress.startWrite())

        val fresh = sessions.startFresh(true, expectedSessionId = previous.id)

        assertTrue(fresh.session.id != previous.id)
        assertEquals(fresh.session, sessions.active())
        assertTrue(dao.pages(fresh.session.draftDocumentId).isEmpty())
        assertEquals(false, dao.document(previous.draftDocumentId)?.isDraft)
        val saved = documents.observeLibrary().first().single { it.id == previous.draftDocumentId }
        assertEquals(fresh.savedTitle, saved.title)
        assertTrue(saved.title.startsWith("Filed "))
        assertEquals(listOf(page.id), dao.pages(saved.id).map { it.id })
        assertTrue(dao.pages(target).isEmpty())
        assertFalse(progress.baseFile.exists())
    }

    @Test
    fun startFreshDiscardsPreviousDraftAndItsFiles() = runBlocking {
        val previous = sessions.start()
        val page = add(previous.draftDocumentId)
        val progress = files.duplexFile(previous.id)
        progress.finishWrite(progress.startWrite())

        val fresh = sessions.startFresh(false, expectedSessionId = previous.id)

        assertTrue(fresh.session.id != previous.id)
        assertEquals(fresh.session, sessions.active())
        assertNull(fresh.savedTitle)
        assertNull(dao.document(previous.draftDocumentId))
        assertFalse(File(page.originalPath).exists())
        assertFalse(progress.baseFile.exists())
    }

    @Test
    fun startFreshReusesAndRetargetsAnEmptyDraft() = runBlocking {
        val target = saved("Target")
        val previous = sessions.start()

        val fresh = sessions.startFresh(true, target, 2)

        assertEquals(previous.copy(targetDocumentId = target, insertAt = 2), fresh.session)
        assertEquals(fresh.session, sessions.active())
        assertNull(fresh.savedTitle)
        assertEquals(listOf(target), documents.observeLibrary().first().map { it.id })
    }

    @Test
    fun startFreshRejectsConfirmationForAReplacedDraft() = runBlocking {
        val first = sessions.start()
        add(first.draftDocumentId)
        val second = sessions.startFresh(false, expectedSessionId = first.id).session
        val page = add(second.draftDocumentId)

        val failure = runCatching { sessions.startFresh(false, expectedSessionId = first.id) }

        assertTrue(failure.exceptionOrNull() is IllegalStateException)
        assertEquals(second, sessions.active())
        assertEquals(listOf(page.id), dao.pages(second.draftDocumentId).map { it.id })
    }

    @Test
    fun duplexProgressSurvivesReopeningTheStoreAndIsClearedWhenTheSessionFinishes() = runBlocking {
        val session = sessions.start()
        val previous = DuplexProgress(
            phase = DuplexPhase.BACKS,
            enabled = true,
            sides = linkedMapOf(
                "f2" to DuplexPhase.FRONTS,
                "f1" to DuplexPhase.FRONTS,
                "b1" to DuplexPhase.BACKS,
            ),
        )
        FileDuplexProgressStore(files).write(session.id, previous)

        val reopened = FileDuplexProgressStore(files)
        val actual = reopened.read(session.id)
        assertEquals(previous, actual)
        assertEquals(previous.sides.keys.toList(), actual.sides.keys.toList())

        sessions.finish(session.id)

        assertEquals(DuplexProgress(), reopened.read(session.id))
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
