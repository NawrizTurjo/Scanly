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
import dev.turjo.paperless.core.imaging.CardPlacement
import dev.turjo.paperless.core.imaging.IdCardLayout
import dev.turjo.paperless.core.model.Tag
import dev.turjo.paperless.core.model.TagSummary
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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock

private const val ID_COMPOSITE_PREFIX = "id-composite-"

val Page.isIdComposite: Boolean get() = id.startsWith(ID_COMPOSITE_PREFIX)

internal fun idCompositeId(lastSourceId: String) = ID_COMPOSITE_PREFIX + lastSourceId

data class IdComposite(
    val pageId: String,
    val sourceIds: List<String?>,
    val layout: IdCardLayout,
) {
    constructor(pageId: String, frontId: String?, backId: String, layout: IdCardLayout) :
        this(pageId, listOf(frontId, backId), layout)
}

internal fun String?.remapIdSources(ids: Map<String, String>): String? =
    this?.split(',')?.joinToString(",") { ids[it].orEmpty() }

internal fun remapPageIds(pages: List<PageEntity>): Map<String, String> {
    val ids = pages.associate { it.id to UUID.randomUUID().toString() }.toMutableMap()
    for (page in pages) {
        if (page.id.startsWith(ID_COMPOSITE_PREFIX)) {
            val lastSourceId = page.id.removePrefix(ID_COMPOSITE_PREFIX)
            ids[page.id] = idCompositeId(ids[lastSourceId] ?: UUID.randomUUID().toString())
        }
    }
    return ids
}

// The composite ID already stores the last source. The existing link column stores the preceding sources.
private fun PageEntity.toIdComposite() = IdComposite(
    id,
    idFrontId?.split(',')?.map { it.ifEmpty { null } }.orEmpty().ifEmpty { listOf(null) } +
        id.removePrefix(ID_COMPOSITE_PREFIX),
    idLayout?.let(::parseIdCardLayout) ?: IdCardLayout(),
)

internal fun parseIdCardLayout(encoded: String): IdCardLayout {
    val sections = encoded.split(';')
    val fields = sections.first().split(',')
    val (arrangement, size, gap, outline) = fields
    fun placement(start: Int): CardPlacement? {
        val left = fields.getOrNull(start)?.toFloatOrNull() ?: return null
        val top = fields.getOrNull(start + 1)?.toFloatOrNull() ?: return null
        val width = fields.getOrNull(start + 2)?.toFloatOrNull() ?: return null
        return CardPlacement(left, top, width)
    }
    return IdCardLayout(
        IdCardLayout.Arrangement.valueOf(arrangement), IdCardLayout.Size.valueOf(size),
        gap.toInt(), outline.toBooleanStrict(), placement(5), placement(8),
        reversed = fields.getOrNull(4)?.toBooleanStrict() ?: false,
        extraPlacements = (11 until fields.size step 3).map(::placement),
        order = sections.getOrNull(1)?.split(',')?.map(String::toInt).orEmpty(),
    )
}

internal fun IdCardLayout.encode(): String {
    val preset = "${arrangement.name},${size.name},$gapMm,$outline"
    fun CardPlacement?.fields() = if (this == null) ",," else "$left,$top,$width"
    val placements = if (reversed || front != null || back != null || extraPlacements.isNotEmpty()) {
        (listOf(front, back) + extraPlacements).joinToString(",", prefix = ",$reversed,") { it.fields() }
    } else ""
    val orderSuffix = if (order.isEmpty()) "" else order.joinToString(",", prefix = ";")
    return preset + placements + orderSuffix
}

interface DocumentRepository {
    fun observeLibrary(): Flow<List<DocumentSummary>> = observeLibrary(LibraryFilter())
    fun observeLibrary(filter: LibraryFilter): Flow<List<DocumentSummary>> =
        throw UnsupportedOperationException("Filtered library unavailable")
    fun observeTags(): Flow<List<TagSummary>> = flowOf(emptyList())
    fun observeDocumentTags(id: String): Flow<List<Tag>> = flowOf(emptyList())
    suspend fun createTag(name: String): String = throw UnsupportedOperationException("Tags unavailable")
    suspend fun renameTag(id: String, name: String): Unit = throw UnsupportedOperationException("Tags unavailable")
    suspend fun deleteTag(id: String): Unit = throw UnsupportedOperationException("Tags unavailable")
    suspend fun setDocumentTags(id: String, tagIds: Set<String>, newName: String? = null): Unit =
        throw UnsupportedOperationException("Tags unavailable")
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
    suspend fun updatePageEdits(page: Page) = updatePage(page)
    suspend fun addPage(documentId: String, originalFile: File, width: Int, height: Int, at: Int? = null): Page
    suspend fun addDraftPage(draftId: String, originalFile: File, width: Int, height: Int): Page =
        addPage(draftId, originalFile, width, height)
    suspend fun addDraftPage(draftId: String, originalFile: File, width: Int, height: Int, mode: PageMode): Page =
        addDraftPage(draftId, originalFile, width, height)
    suspend fun addPageCopy(sourcePageId: String, at: Int): Page =
        throw UnsupportedOperationException("Page copying unavailable")
    suspend fun splitBookPage(left: Page, rightQuad: Quad): Page =
        throw UnsupportedOperationException("Book splitting unavailable")
    /** Manual split: [left] replaces the page, a copy with [rightQuad] is inserted right after it. */
    suspend fun splitPage(left: Page, rightQuad: Quad): Page =
        throw UnsupportedOperationException("Page splitting unavailable")
    suspend fun addIdComposite(front: Page, back: Page, original: File, width: Int, height: Int): Page =
        throw UnsupportedOperationException("ID composition unavailable")
    suspend fun addIdComposite(sources: List<Page>, original: File, width: Int, height: Int): Page =
        if (sources.size == 2) addIdComposite(sources[0], sources[1], original, width, height)
        else throw UnsupportedOperationException("Multi-side ID composites unavailable")
    suspend fun idComposite(pageId: String): IdComposite? = null
    suspend fun idCompositesFor(side: Page): List<IdComposite> = emptyList()
    suspend fun replaceIdComposite(composite: IdComposite, original: File): Boolean = false
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
    private val tags = database.tagDao()

    override fun observeLibrary(filter: LibraryFilter) =
        dao.observeTaggedLibrary(filter.folderId, filter.favouritesOnly, filter.tagId)
        .map { rows ->
            val zone = ZoneId.systemDefault()
            val search = parseLibrarySearch(filter.query, LocalDate.now(zone))
            val matching = rows.filter { (row, labels) ->
                search.matchesText(row.title, row.folderName, labels.map(TagEntity::name)) &&
                    search.matchesDate(Instant.ofEpochMilli(row.createdAt).atZone(zone).toLocalDate())
            }
            val sorted = when (filter.sort) {
                LibrarySort.UPDATED_DESC -> matching
                LibrarySort.NAME -> matching.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it: TaggedLibraryRow ->
                    it.row.title
                })
                LibrarySort.CREATED_DESC -> matching.sortedByDescending { it.row.createdAt }
            }
            sorted.map { (row, labels) ->
                val thumb = row.coverId?.let(files::thumbFile)?.takeIf(File::isFile)
                DocumentSummary(
                    row.id, row.title, row.pageCount, thumb?.path, row.updatedAt, row.isFavorite,
                    labels.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, TagEntity::name)).map(TagEntity::toModel),
                )
            }
        }.flowOn(Dispatchers.IO)

    override fun observeTags() = tags.observeTags().map { rows ->
        rows.map { TagSummary(it.tag.toModel(), it.documentCount) }
    }

    override fun observeDocumentTags(id: String) =
        tags.observeDocumentTags(id).map { rows -> rows.map(TagEntity::toModel) }

    override suspend fun createTag(name: String): String = transaction { insertTag(name) }

    override suspend fun renameTag(id: String, name: String) = tags.rename(id, validatedTagName(name))

    override suspend fun deleteTag(id: String) = tags.delete(id)

    override suspend fun setDocumentTags(id: String, tagIds: Set<String>, newName: String?) = transaction {
        requireDocument(id)
        val newId = newName?.let { insertTag(it) }
        val links = tagIds.map { DocumentTagEntity(id, it) } + listOfNotNull(newId?.let { DocumentTagEntity(id, it) })
        tags.clearLinks(id)
        tags.insertLinks(links)
    }

    private suspend fun insertTag(name: String): String {
        val id = UUID.randomUUID().toString()
        val color = ((tags.lastColor() ?: -1) + 1) % Tag.COLOR_COUNT
        tags.insert(TagEntity(id, validatedTagName(name), color, System.currentTimeMillis()))
        return id
    }

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
                val pages = dao.pages(id)
                val ids = remapPageIds(pages)
                for (page in pages) {
                    val pageId = ids.getValue(page.id)
                    copiedIds.add(pageId)
                    val original = File(page.originalPath).copyTo(files.originalFile(pageId))
                    val processed = page.processedPath?.let(::File)?.takeIf(File::isFile)
                        ?.copyTo(files.processedFile(pageId))
                    val placements = files.signaturePlacements(page.id).readSignatures()
                    if (placements.isNotEmpty()) files.signaturePlacements(pageId).writeSignatures(placements)
                    files.thumbFile(page.id).takeIf(File::isFile)?.copyTo(files.thumbFile(pageId))
                    dao.insertPage(page.copy(
                        id = pageId, documentId = copyId, originalPath = original.path,
                        idFrontId = page.idFrontId.remapIdSources(ids),
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
        row?.let {
            DocumentWithPages(it.document.toModel(), it.pages.sortedBy(PageEntity::index).map(files::pageWithSignatures))
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun getPage(id: String): Page? =
        withContext(Dispatchers.IO) { dao.page(id)?.let(files::pageWithSignatures) }

    override suspend fun updatePage(page: Page) = updatePage(page, saveSignatures = false)

    override suspend fun updatePageEdits(page: Page) = updatePage(page, saveSignatures = true)

    private suspend fun updatePage(page: Page, saveSignatures: Boolean) = transaction {
        val existing = requireNotNull(dao.page(page.id)) { "Unknown page ${page.id}" }
        if (page.isIdComposite) return@transaction
        require(page.rotation in setOf(0, 90, 180, 270))
        require(page.brightness in -1f..1f && page.contrast in -1f..1f)
        dao.updatePage(page.copy(documentId = existing.documentId, index = existing.index).toEntity())
        touch(requireDocument(existing.documentId))
        if (saveSignatures) savePageSignatures(page)
    }

    private fun savePageSignatures(page: Page) {
        val sidecar = files.signaturePlacements(page.id)
        if (sidecar.readSignatures() != page.signatures) sidecar.writeSignatures(page.signatures)
    }

    override suspend fun addPage(documentId: String, originalFile: File, width: Int, height: Int, at: Int?): Page =
        addPage(documentId, originalFile, width, height, at, requireDraft = false)

    override suspend fun addDraftPage(draftId: String, originalFile: File, width: Int, height: Int): Page =
        addPage(draftId, originalFile, width, height, null, requireDraft = true)

    override suspend fun addDraftPage(draftId: String, originalFile: File, width: Int, height: Int, mode: PageMode): Page =
        addPage(draftId, originalFile, width, height, null, requireDraft = true, mode = mode)

    private suspend fun addPage(
        documentId: String, originalFile: File, width: Int, height: Int, at: Int?, requireDraft: Boolean, mode: PageMode = PageMode.NORMAL,
    ): Page = withContext(Dispatchers.IO) {
        require(width > 0 && height > 0)
        val pageId = UUID.randomUUID().toString()
        val original = files.originalFile(pageId)
        // Keep cancellation from splitting file creation and its database transaction.
        withContext(NonCancellable) {
            files.pageCreationLock.withLock {
                addPageFilesAndRow(documentId, originalFile, width, height, at, pageId, original, requireDraft, mode)
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
        requireDraft: Boolean,
        mode: PageMode,
    ): Page {
        return try {
            originalFile.copyTo(original)
            thumbnails.make(original, pageId)
            database.withTransaction {
                val document = requireDocument(documentId)
                check(document.deletedAt == null && (!requireDraft || document.isDraft))
                val index = insertionIndex(at, dao.pages(documentId).size)
                val page = Page(
                    pageId, documentId, index, mode, original.path, width, height,
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

    override suspend fun splitPage(left: Page, rightQuad: Quad): Page =
        insertDerivedPage(
            "split-${UUID.randomUUID()}",
            beforeCommit = { savePageSignatures(left) },
        ) { id ->
            val current = requireNotNull(dao.page(left.id))
            val updated = left.copy(index = current.index, documentId = current.documentId)
            dao.updatePage(updated.toEntity())
            updated.asDerivedPage(id, current.index + 1, File(updated.originalPath))
                .copy(quad = rightQuad, signatures = emptyList())
        }

    override suspend fun addIdComposite(front: Page, back: Page, original: File, width: Int, height: Int): Page =
        addIdComposite(listOf(front, back), original, width, height)

    override suspend fun addIdComposite(sources: List<Page>, original: File, width: Int, height: Int): Page {
        require(sources.size in 2..6 && sources.distinctBy(Page::id).size == sources.size)
        val last = sources.last()
        val preceding = sources.dropLast(1)
        return insertDerivedPage(idCompositeId(last.id), idFrontId = preceding.joinToString(",") { it.id }) { id ->
            val current = requireNotNull(dao.page(last.id)).toModel()
            check(preceding.all { dao.page(it.id)?.documentId == current.documentId })
            current.asDerivedPage(id, current.index + 1, original).copy(
                originalWidth = width, originalHeight = height, mode = PageMode.NORMAL, filter = PageFilter.ORIGINAL,
                quad = null, rotation = 0, brightness = 0f, contrast = 0f,
                deskew = false, shadowFix = false, glareFix = false, inkRemoval = null,
            )
        }
    }

    override suspend fun idComposite(pageId: String): IdComposite? =
        if (pageId.startsWith(ID_COMPOSITE_PREFIX)) dao.page(pageId)?.toIdComposite() else null

    override suspend fun idCompositesFor(side: Page): List<IdComposite> =
        dao.pages(side.documentId).filter { it.id.startsWith(ID_COMPOSITE_PREFIX) }
            .map(PageEntity::toIdComposite).filter { side.id in it.sourceIds }

    override suspend fun replaceIdComposite(composite: IdComposite, original: File): Boolean =
        withContext(Dispatchers.IO + NonCancellable) {
            var staged: File? = null
            var previousImages = emptySet<File>()
            val replaced = try {
                database.withTransaction {
                    val page = dao.page(composite.pageId) ?: return@withTransaction false
                    if (composite.sourceIds.none { it != null && dao.page(it)?.documentId == page.documentId }) {
                        return@withTransaction false
                    }
                    val image = File.createTempFile("id-card-", ".jpg", files.pageDir(page.id))
                    staged = image
                    original.copyTo(image, overwrite = true)
                    val clean = page.toModel().copy(
                        originalPath = image.path, processedPath = image.path, filter = PageFilter.ORIGINAL,
                        quad = null, rotation = 0, brightness = 0f, contrast = 0f,
                        deskew = false, shadowFix = false, glareFix = false, inkRemoval = null,
                        glareWarning = false,
                    )
                    dao.updatePage(clean.copy(processedHash = clean.paramsHash()).toEntity().copy(
                        idFrontId = page.idFrontId, idLayout = composite.layout.encode(),
                    ))
                    touch(requireDocument(page.documentId))
                    thumbnails.make(image, page.id)
                    previousImages = setOfNotNull(page.originalPath, page.processedPath).map(::File).toSet()
                    true
                }
            } catch (error: Exception) {
                staged?.delete()
                throw error
            }
            if (replaced) previousImages.forEach { it.delete() }
            replaced
        }

    private fun Page.asDerivedPage(id: String, index: Int, source: File): Page =
        copy(
            id = id, index = index, originalPath = source.path, processedPath = null,
            processedHash = null, glareWarning = false, createdAt = System.currentTimeMillis(),
        )

    private suspend fun insertDerivedPage(
        id: String,
        idFrontId: String? = null,
        beforeCommit: () -> Unit = {},
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
                    val stored = if (idFrontId != null) {
                        page.copy(originalPath = original.path, processedPath = original.path, processedHash = page.paramsHash())
                    } else {
                        page.copy(originalPath = original.path)
                    }
                    dao.shiftForInsert(page.documentId, page.index)
                    dao.insertPage(stored.toEntity().copy(idFrontId = idFrontId, idLayout = idFrontId?.let { IdCardLayout().encode() }))
                    touch(document)
                    beforeCommit()
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
        if (page.id.startsWith(ID_COMPOSITE_PREFIX)) return@transaction
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
