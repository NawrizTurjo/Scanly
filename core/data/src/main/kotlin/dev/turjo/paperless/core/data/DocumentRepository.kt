package dev.turjo.paperless.core.data

import androidx.room.withTransaction
import dev.turjo.paperless.core.model.DeletedDocument
import dev.turjo.paperless.core.model.LibraryFilter
import dev.turjo.paperless.core.model.LibrarySort
import dev.turjo.paperless.core.model.DocumentSummary
import dev.turjo.paperless.core.model.DocumentWithPages
import dev.turjo.paperless.core.model.Page
import dev.turjo.paperless.core.model.PageFilter
import dev.turjo.paperless.core.model.PageMode
import dev.turjo.paperless.core.model.Quad
import dev.turjo.paperless.core.model.paramsHash
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock

interface DocumentRepository {
    fun observeLibrary(): Flow<List<DocumentSummary>> = observeLibrary(LibraryFilter())
    fun observeLibrary(filter: LibraryFilter): Flow<List<DocumentSummary>> =
        throw UnsupportedOperationException("Filtered library unavailable")
    fun observeDeleted(): Flow<List<DeletedDocument>> =
        throw UnsupportedOperationException("Recycle bin unavailable")
    suspend fun duplicate(id: String): String = throw UnsupportedOperationException("Duplication unavailable")
    suspend fun deleteForever(id: String): Unit = throw UnsupportedOperationException("Recycle bin unavailable")
    suspend fun purgeDeletedOlderThan(days: Int): Unit = throw UnsupportedOperationException("Recycle bin unavailable")
    suspend fun softDelete(ids: Set<String>) = ids.forEach { softDelete(it) }
    suspend fun restore(ids: Set<String>) = ids.forEach { restore(it) }
    suspend fun setFavorite(ids: Set<String>, isFavorite: Boolean) = ids.forEach { setFavorite(it, isFavorite) }
    fun observeDocument(id: String): Flow<DocumentWithPages?>
    suspend fun getPage(id: String): Page?
    suspend fun updatePage(page: Page)
    suspend fun addPage(documentId: String, originalFile: File, width: Int, height: Int, at: Int? = null): Page
    suspend fun addPageCopy(sourcePageId: String, at: Int): Page =
        throw UnsupportedOperationException("Page copying unavailable")
    suspend fun splitBookPage(left: Page, rightQuad: Quad): Page =
        throw UnsupportedOperationException("Book splitting unavailable")
    suspend fun addIdComposite(back: Page, original: File, width: Int, height: Int): Page =
        throw UnsupportedOperationException("ID composition unavailable")
    suspend fun reorderPages(documentId: String, orderedPageIds: List<String>)
    suspend fun deletePage(pageId: String)
    suspend fun rotatePage(pageId: String, delta: Int)
    suspend fun rename(id: String, title: String)
    suspend fun setFavorite(id: String, isFavorite: Boolean)
    suspend fun softDelete(id: String)
    suspend fun restore(id: String)
    suspend fun splitOff(draftId: String, pageIds: List<String>, title: String): String =
        throw UnsupportedOperationException("Splitting unavailable")
    suspend fun commitDraft(
        draftId: String,
        title: String,
        targetDocumentId: String? = null,
        insertAt: Int? = null,
    ): String
}

fun defaultTitle(now: Long): String = DateTimeFormatter.ofPattern("'Scan' yyyy-MM-dd HH.mm", Locale.ROOT)
    .withZone(ZoneId.systemDefault())
    .format(Instant.ofEpochMilli(now))

class RoomDocumentRepository @Inject constructor(
    private val database: PaperlessDatabase,
    private val dao: DocumentDao,
    private val files: FileStore,
    private val thumbnails: ThumbnailMaker,
) : DocumentRepository {
    override fun observeLibrary(filter: LibraryFilter) = dao.observeLibrary(filter.folderId, filter.favouritesOnly)
        .map { rows ->
            val zone = ZoneId.systemDefault()
            val search = parseLibrarySearch(filter.query, LocalDate.now(zone))
            val matching = rows.filter { row ->
                (row.title.contains(search.text, ignoreCase = true) ||
                    row.folderName?.contains(search.text, ignoreCase = true) == true) &&
                    search.matchesDate(Instant.ofEpochMilli(row.createdAt).atZone(zone).toLocalDate())
            }
            val sorted = when (filter.sort) {
                LibrarySort.UPDATED_DESC -> matching
                LibrarySort.NAME -> matching.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it: LibraryRow ->
                    it.title
                })
                LibrarySort.CREATED_DESC -> matching.sortedByDescending(LibraryRow::createdAt)
            }
            sorted.map { row ->
                val thumb = row.coverId?.let(files::thumbFile)?.takeIf(File::isFile)
                DocumentSummary(row.id, row.title, row.pageCount, thumb?.path, row.updatedAt, row.isFavorite)
            }
        }.flowOn(Dispatchers.IO)

    override fun observeDeleted() = dao.observeDeleted()

    override suspend fun duplicate(id: String): String = withContext(Dispatchers.IO + NonCancellable) {
        val copiedIds = mutableListOf<String>()
        try {
            database.withTransaction {
                val source = requireDocument(id)
                check(!source.isDraft && source.deletedAt == null)
                val copyId = UUID.randomUUID().toString()
                val now = System.currentTimeMillis()
                val copy = source.copy(
                    id = copyId, title = "Copy of ${source.title}", createdAt = now, updatedAt = now,
                    coverPageId = null, lastOpenedAt = 0, lastPage = 0, pageCount = 0,
                )
                dao.insertDocument(copy)
                var coverId: String? = null
                for (page in dao.pages(id)) {
                    val pageId = UUID.randomUUID().toString()
                    copiedIds.add(pageId)
                    val original = File(page.originalPath).copyTo(files.originalFile(pageId))
                    val processed = page.processedPath?.let(::File)?.takeIf(File::isFile)
                        ?.copyTo(files.processedFile(pageId))
                    files.thumbFile(page.id).takeIf(File::isFile)?.copyTo(files.thumbFile(pageId))
                    dao.insertPage(page.copy(
                        id = pageId, documentId = copyId, originalPath = original.path,
                        processedPath = processed?.path, processedHash = page.processedHash.takeIf { processed != null },
                    ))
                    if (page.id == source.coverPageId) coverId = pageId
                }
                dao.updateDocument(copy.copy(coverPageId = coverId))
                copyId
            }
        } catch (error: Exception) {
            for (pageId in copiedIds) {
                try {
                    files.deletePageFiles(pageId)
                } catch (cleanupError: Exception) {
                    error.addSuppressed(cleanupError)
                }
            }
            throw error
        }
    }

    override suspend fun deleteForever(id: String) = deleteFromBin(id, null)

    override suspend fun purgeDeletedOlderThan(days: Int) {
        require(days >= 0)
        val cutoff = System.currentTimeMillis() - Duration.ofDays(days.toLong()).toMillis()
        val ids = withContext(Dispatchers.IO) { dao.expiredDocuments(cutoff) }
        ids.forEach { deleteFromBin(it, cutoff) }
    }

    private suspend fun deleteFromBin(id: String, cutoff: Long?) = withContext(Dispatchers.IO + NonCancellable) {
        val pageIds = database.withTransaction {
            val deletedAt = dao.document(id)?.deletedAt ?: return@withTransaction null
            if (cutoff != null && deletedAt >= cutoff) return@withTransaction null
            val pages = dao.pages(id).map(PageEntity::id)
            dao.deleteDocument(id)
            pages
        } ?: return@withContext
        var failure: java.io.IOException? = try {
            files.deleteRestoredPdf(id)
            null
        } catch (error: java.io.IOException) {
            error
        }
        for (pageId in pageIds) {
            try {
                files.deletePageFiles(pageId)
            } catch (error: java.io.IOException) {
                if (failure == null) failure = error else failure.addSuppressed(error)
            }
        }
        if (failure != null) throw failure
    }

    override fun observeDocument(id: String) = dao.observeDocument(id).map { row ->
        row?.let { DocumentWithPages(it.document.toModel(), it.pages.sortedBy(PageEntity::index).map(PageEntity::toModel)) }
    }.flowOn(Dispatchers.IO)

    override suspend fun getPage(id: String): Page? =
        withContext(Dispatchers.IO) { dao.page(id)?.toModel() }

    override suspend fun updatePage(page: Page) = transaction {
        val existing = requireNotNull(dao.page(page.id)) { "Unknown page ${page.id}" }
        require(page.rotation in setOf(0, 90, 180, 270))
        require(page.brightness in -1f..1f && page.contrast in -1f..1f)
        dao.updatePage(page.copy(documentId = existing.documentId, index = existing.index).toEntity())
        touch(requireDocument(existing.documentId))
    }

    override suspend fun addPage(documentId: String, originalFile: File, width: Int, height: Int, at: Int?): Page =
        withContext(Dispatchers.IO) {
            require(width > 0 && height > 0)
            val pageId = UUID.randomUUID().toString()
            val original = files.originalFile(pageId)
            // Keep cancellation from splitting file creation and its database transaction.
            withContext(NonCancellable) {
                files.pageCreationLock.withLock {
                    addPageFilesAndRow(documentId, originalFile, width, height, at, pageId, original)
                }
            }
        }

    private suspend fun addPageFilesAndRow(
        documentId: String,
        originalFile: File,
        width: Int,
        height: Int,
        at: Int?,
        pageId: String,
        original: File,
    ): Page {
        return try {
            originalFile.copyTo(original)
            thumbnails.make(original, pageId)
            database.withTransaction {
                val document = requireDocument(documentId)
                check(document.deletedAt == null)
                val index = insertionIndex(at, dao.pages(documentId).size)
                val page = Page(
                    pageId, documentId, index, PageMode.NORMAL, original.path, width, height,
                    null, 0, PageFilter.ORIGINAL, 0f, 0f, false, false,
                    null, null, false, System.currentTimeMillis(),
                )
                dao.shiftForInsert(documentId, index)
                dao.insertPage(page.toEntity())
                touch(document)
                page
            }
        } catch (error: Exception) {
            try {
                files.deletePageFiles(pageId)
            } catch (cleanupError: Exception) {
                error.addSuppressed(cleanupError)
            }
            throw error
        }
    }

    override suspend fun addPageCopy(sourcePageId: String, at: Int): Page =
        insertDerivedPage(UUID.randomUUID().toString()) { id ->
            val source = requireNotNull(dao.page(sourcePageId)).toModel()
            val count = dao.pages(source.documentId).size
            source.asDerivedPage(id, insertionIndex(at, count), File(source.originalPath))
        }

    override suspend fun splitBookPage(left: Page, rightQuad: Quad): Page =
        insertDerivedPage("book-right-${left.id}") { id ->
            val current = requireNotNull(dao.page(left.id))
            val updated = left.copy(index = current.index, documentId = current.documentId)
            dao.updatePage(updated.toEntity())
            updated.asDerivedPage(id, current.index + 1, File(updated.originalPath))
                .copy(mode = PageMode.BOOK_R, quad = rightQuad)
        }

    override suspend fun addIdComposite(back: Page, original: File, width: Int, height: Int): Page =
        insertDerivedPage("id-composite-${back.id}", renderedOriginal = true) { id ->
            val current = requireNotNull(dao.page(back.id)).toModel()
            current.asDerivedPage(id, current.index + 1, original).copy(
                originalWidth = width, originalHeight = height, mode = PageMode.NORMAL, filter = PageFilter.ORIGINAL,
                quad = null, rotation = 0, brightness = 0f, contrast = 0f,
                deskew = false, shadowFix = false, glareFix = false, inkRemoval = null,
            )
        }

    private fun Page.asDerivedPage(id: String, index: Int, source: File): Page =
        copy(
            id = id, index = index, originalPath = source.path, processedPath = null,
            processedHash = null, glareWarning = false, createdAt = System.currentTimeMillis(),
        )

    private suspend fun insertDerivedPage(
        id: String,
        renderedOriginal: Boolean = false,
        build: suspend (String) -> Page,
    ): Page =
        withContext(Dispatchers.IO + NonCancellable) {
            var createdFiles = false
            try {
                database.withTransaction {
                    dao.page(id)?.let { return@withTransaction it.toModel() }
                    val page = build(id)
                    val document = requireDocument(page.documentId)
                    check(document.deletedAt == null)
                    createdFiles = true
                    val original = File(page.originalPath).copyTo(files.originalFile(id), overwrite = true)
                    thumbnails.make(original, id)
                    val stored = if (renderedOriginal) {
                        page.copy(originalPath = original.path, processedPath = original.path, processedHash = page.paramsHash())
                    } else {
                        page.copy(originalPath = original.path)
                    }
                    dao.shiftForInsert(page.documentId, page.index)
                    dao.insertPage(stored.toEntity())
                    touch(document)
                    stored
                }
            } catch (error: Exception) {
                if (createdFiles) {
                    try {
                        files.deletePageFiles(id)
                    } catch (cleanupError: Exception) {
                        error.addSuppressed(cleanupError)
                    }
                }
                throw error
            }
        }

    override suspend fun reorderPages(documentId: String, orderedPageIds: List<String>) = transaction {
        val document = requireDocument(documentId)
        val current = dao.pages(documentId).map(PageEntity::id)
        require(current.size == orderedPageIds.size && current.toSet() == orderedPageIds.toSet())
        orderedPageIds.forEachIndexed { index, id -> dao.setPagePosition(id, documentId, index) }
        touch(document)
    }

    override suspend fun deletePage(pageId: String) = withContext(Dispatchers.IO) {
        withContext(NonCancellable) {
            database.withTransaction {
                val page = dao.page(pageId) ?: return@withTransaction
                val document = requireDocument(page.documentId)
                dao.deletePage(pageId)
                dao.shiftAfterDelete(page.documentId, page.index)
                touch(document.copy(coverPageId = document.coverPageId?.takeUnless { it == pageId }))
            }
            files.deletePageFiles(pageId)
        }
    }

    override suspend fun rotatePage(pageId: String, delta: Int) = transaction {
        require(delta % 90 == 0)
        val page = requireNotNull(dao.page(pageId)) { "Unknown page $pageId" }
        val rotation = Math.floorMod(page.rotation + delta % 360, 360)
        dao.updatePage(page.copy(rotation = rotation))
        touch(requireDocument(page.documentId))
    }

    override suspend fun rename(id: String, title: String) = transaction {
        touch(requireDocument(id).copy(title = title))
    }

    override suspend fun setFavorite(id: String, isFavorite: Boolean) = transaction {
        touch(requireDocument(id).copy(isFavorite = isFavorite))
    }

    override suspend fun softDelete(id: String) = softDelete(setOf(id))

    override suspend fun softDelete(ids: Set<String>) = transaction {
        val now = System.currentTimeMillis()
        ids.forEach { id ->
            val document = requireDocument(id)
            if (document.deletedAt == null) touch(document.copy(deletedAt = now))
        }
    }

    override suspend fun restore(id: String) = restore(setOf(id))

    override suspend fun restore(ids: Set<String>) = transaction {
        ids.forEach { id ->
            val document = dao.document(id)
            if (document?.deletedAt != null) touch(document.copy(deletedAt = null))
        }
    }

    override suspend fun setFavorite(ids: Set<String>, isFavorite: Boolean) = transaction {
        ids.forEach { id ->
            val document = requireDocument(id)
            if (document.deletedAt == null && document.isFavorite != isFavorite) {
                touch(document.copy(isFavorite = isFavorite))
            }
        }
    }

    override suspend fun commitDraft(draftId: String, title: String, targetDocumentId: String?, insertAt: Int?) =
        transaction {
            val draft = requireDocument(draftId)
            check(draft.isDraft && draft.deletedAt == null)
            if (targetDocumentId == null) {
                touch(draft.copy(title = title, isDraft = false))
                draftId
            } else {
                val target = requireDocument(targetDocumentId)
                check(!target.isDraft && target.deletedAt == null)
                val index = insertionIndex(insertAt, dao.pages(target.id).size)
                val pages = dao.pages(draftId)
                pages.forEachIndexed { offset, page ->
                    dao.shiftForInsert(target.id, index + offset)
                    dao.setPagePosition(page.id, target.id, index + offset)
                }
                dao.deleteDocument(draftId)
                touch(target)
                target.id
            }
        }

    override suspend fun splitOff(draftId: String, pageIds: List<String>, title: String): String = transaction {
        val draft = requireDocument(draftId)
        check(draft.isDraft && draft.deletedAt == null)
        val pages = dao.pages(draftId)
        val moved = pageIds.toSet()
        require(pageIds.isNotEmpty() && moved.size == pageIds.size && pages.map { it.id }.containsAll(moved))
        val remaining = pages.filterNot { it.id in moved }
        require(remaining.isNotEmpty())
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        dao.insertDocument(draft.copy(
            id = id, title = title, isDraft = false, createdAt = now, updatedAt = now, coverPageId = pageIds.first(),
        ))
        pageIds.forEachIndexed { index, pageId -> dao.setPagePosition(pageId, id, index) }
        remaining.forEachIndexed { index, page -> dao.setPagePosition(page.id, draftId, index) }
        touch(draft.copy(coverPageId = draft.coverPageId?.takeUnless { it in moved }))
        id
    }

    private suspend fun <T> transaction(block: suspend () -> T): T =
        withContext(Dispatchers.IO) { database.withTransaction(block) }

    private suspend fun requireDocument(id: String): DocumentEntity =
        requireNotNull(dao.document(id)) { "Unknown document $id" }

    private suspend fun touch(document: DocumentEntity) {
        dao.updateDocument(document.copy(updatedAt = maxOf(System.currentTimeMillis(), document.updatedAt + 1)))
    }

    private fun insertionIndex(at: Int?, count: Int): Int =
        (at ?: count).also { require(it in 0..count) { "Invalid page index $it for $count pages" } }
}
