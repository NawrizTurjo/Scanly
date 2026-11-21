package dev.turjo.paperless.core.data

import androidx.room.withTransaction
import dev.turjo.paperless.core.model.DocumentSummary
import dev.turjo.paperless.core.model.DocumentWithPages
import dev.turjo.paperless.core.model.Page
import dev.turjo.paperless.core.model.PageFilter
import dev.turjo.paperless.core.model.PageMode
import java.io.File
import java.time.Instant
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

interface DocumentRepository {
    fun observeLibrary(): Flow<List<DocumentSummary>>
    fun observeDocument(id: String): Flow<DocumentWithPages?>
    suspend fun getPage(id: String): Page?
    suspend fun updatePage(page: Page)
    suspend fun addPage(documentId: String, originalFile: File, width: Int, height: Int, at: Int? = null): Page
    suspend fun reorderPages(documentId: String, orderedPageIds: List<String>)
    suspend fun deletePage(pageId: String)
    suspend fun rotatePage(pageId: String, delta: Int)
    suspend fun rename(id: String, title: String)
    suspend fun setFavorite(id: String, isFavorite: Boolean)
    suspend fun softDelete(id: String)
    suspend fun restore(id: String)
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
    override fun observeLibrary() = dao.observeLibrary().map { rows ->
        rows.map { row ->
            val thumb = row.coverId?.let(files::thumbFile)?.takeIf(File::isFile)
            DocumentSummary(row.id, row.title, row.pageCount, thumb?.path, row.updatedAt, row.isFavorite)
        }
    }.flowOn(Dispatchers.IO)

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
                try {
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

    override suspend fun softDelete(id: String) = transaction {
        touch(requireDocument(id).copy(deletedAt = System.currentTimeMillis()))
    }

    override suspend fun restore(id: String) = transaction {
        touch(requireDocument(id).copy(deletedAt = null))
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

    private suspend fun <T> transaction(block: suspend () -> T): T =
        withContext(Dispatchers.IO) { database.withTransaction(block) }

    private suspend fun requireDocument(id: String): DocumentEntity =
        requireNotNull(dao.document(id)) { "Unknown document $id" }

    private suspend fun touch(document: DocumentEntity) {
        dao.updateDocument(document.copy(updatedAt = System.currentTimeMillis()))
    }

    private fun insertionIndex(at: Int?, count: Int): Int =
        (at ?: count).also { require(it in 0..count) { "Invalid page index $it for $count pages" } }
}
