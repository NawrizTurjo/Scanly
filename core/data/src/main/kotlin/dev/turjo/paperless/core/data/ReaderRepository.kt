package dev.turjo.paperless.core.data

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.net.toUri
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.turjo.paperless.core.model.PdfKey
import dev.turjo.paperless.core.model.RecentPdf
import dev.turjo.paperless.core.model.TabState
import dev.turjo.paperless.core.model.paramsHash
import dev.turjo.paperless.core.pdf.ExportPage
import dev.turjo.paperless.core.pdf.PageSizeOption
import dev.turjo.paperless.core.pdf.PdfExporter
import dev.turjo.paperless.core.pdf.QualityPreset
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.FileTime
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

interface ReaderRepository {
    /** Where the PDF behind [key] can be read: the stored SAF uri for externals, the cached export for scans. */
    suspend fun resolveUri(key: PdfKey): Uri

    fun observeTabs(): Flow<List<TabState>>
    fun observeActiveTabId(): Flow<String?>
    suspend fun saveTabs(tabs: List<TabState>, activeTabId: String?)
    fun observeRecent(): Flow<List<RecentPdf>>
    suspend fun registerExternal(uri: Uri, displayName: String): PdfKey
    suspend fun pdfKeyForDocument(documentId: String): PdfKey
    suspend fun markOpened(key: PdfKey, pageCount: Int, lastPage: Int)
}

@Singleton
class RoomReaderRepository internal constructor(
    private val database: PaperlessDatabase,
    private val context: Context,
    private val enqueueIndex: () -> Unit = {},
    private val writePdf: suspend (DocumentEntity, File) -> Long,
) : ReaderRepository {
    @Inject
    constructor(
        database: PaperlessDatabase,
        @ApplicationContext context: Context,
        processing: PageProcessing,
        files: FileStore,
    ) : this(
        database, context, { LibraryJobs.index(context) },
        scanPdfWriter(database.documentDao(), processing, PdfExporter(context), files),
    )

    private val dao = database.readerDao()
    private val documents = database.documentDao()
    private val exportLock = Mutex()

    override fun observeTabs(): Flow<List<TabState>> = dao.observeTabs().map { rows ->
        rows.map {
            TabState(it.id, PdfKey(it.pdfKey), it.title, it.page, it.zoom, it.scrollOffsetPx, it.viewMode, it.nightMode)
        }
    }.distinctUntilChanged()

    override fun observeActiveTabId(): Flow<String?> = dao.observeActiveTabId().distinctUntilChanged()

    override suspend fun saveTabs(tabs: List<TabState>, activeTabId: String?) {
        database.withTransaction {
            dao.deleteTabs()
            dao.insertTabs(tabs.mapIndexed { index, tab ->
                ReaderTabEntity(
                    tab.tabId, tab.pdfKey.value, index, tab.tabId == activeTabId, tab.page, tab.zoom,
                    tab.scrollOffsetPx, tab.viewMode, tab.nightMode, tab.title,
                )
            })
        }
    }

    override fun observeRecent(): Flow<List<RecentPdf>> = dao.observeRecent().map { rows ->
        rows.map { RecentPdf(PdfKey(it.pdfKey), it.title, it.lastOpenedAt, it.pageCount, it.lastPage) }
    }.distinctUntilChanged()

    override suspend fun registerExternal(uri: Uri, displayName: String): PdfKey = withContext(Dispatchers.IO) {
        val read = Intent.FLAG_GRANT_READ_URI_PERMISSION
        val persisted = persistPermission(uri, read or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) ||
            persistPermission(uri, read)
        val key = database.withTransaction {
            val existing = dao.externalByUri(uri.toString())
            if (existing == null) {
                val id = UUID.randomUUID().toString()
                dao.insertExternal(ExternalPdfEntity(id, uri.toString(), displayName, persistedPermission = persisted))
                PdfKey(id)
            } else {
                dao.updateExternal(existing.copy(
                    displayName = displayName,
                    persistedPermission = persisted || existing.persistedPermission,
                ))
                PdfKey(existing.id)
            }
        }
        enqueueIndex()
        key
    }

    override suspend fun pdfKeyForDocument(documentId: String): PdfKey {
        ensureDocumentPdf(documentId)
        enqueueIndex()
        return PdfKey("$DOCUMENT_PREFIX$documentId")
    }

    override suspend fun markOpened(key: PdfKey, pageCount: Int, lastPage: Int) {
        val now = System.currentTimeMillis()
        val documentId = key.documentId()
        if (documentId == null) dao.markExternalOpened(key.value, now, pageCount, lastPage)
        else dao.markDocumentOpened(documentId, now, pageCount, lastPage)
    }

    override suspend fun resolveUri(key: PdfKey): Uri {
        val documentId = key.documentId()
        val uri = if (documentId != null) {
            Uri.fromFile(ensureDocumentPdf(documentId))
        } else {
            (dao.externalUri(key.value) ?: throw FileNotFoundException("Unknown external PDF ${key.value}")).toUri()
        }
        enqueueIndex()
        return uri
    }

    internal suspend fun readerTitle(key: PdfKey): String? {
        val documentId = key.documentId()
        return if (documentId == null) dao.externalTitle(key.value) else documents.readableDocument(documentId).title
    }

    private fun persistPermission(uri: Uri, flags: Int): Boolean = try {
        context.contentResolver.takePersistableUriPermission(uri, flags)
        true
    } catch (_: SecurityException) {
        false
    } catch (_: IllegalArgumentException) {
        false
    }

    private suspend fun ensureDocumentPdf(id: String): File = withContext(Dispatchers.IO) {
        require(id.isNotEmpty() && id != "." && id != ".." && '/' !in id && '\\' !in id)
        exportLock.withLock {
            val document = documents.readableDocument(id)
            restoredReaderPdf(context, id, document.updatedAt)?.let { return@withLock it }
            val directory = File(context.cacheDir, "pdf")
            val target = File(directory, "$id.pdf")
            if (target.isFile && target.lastModified() >= document.updatedAt) return@withLock target

            Files.createDirectories(directory.toPath())
            val temp = Files.createTempFile(directory.toPath(), "reader-", ".tmp")
            try {
                val exportedRevision = writePdf(document, temp.toFile())
                // Use the exported revision so an edit during export stays eligible for refresh.
                Files.setLastModifiedTime(temp, FileTime.fromMillis(exportedRevision))
                Files.move(temp, target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
                target
            } finally {
                Files.deleteIfExists(temp)
            }
        }
    }

    private fun PdfKey.documentId(): String? = value.takeIf { it.startsWith(DOCUMENT_PREFIX) }
        ?.removePrefix(DOCUMENT_PREFIX)

}

private const val DOCUMENT_PREFIX = "doc:"

internal fun PdfKey.isLibraryDocument(): Boolean = value.startsWith(DOCUMENT_PREFIX)

private suspend fun DocumentDao.readableDocument(id: String): DocumentEntity =
    document(id)?.takeIf { it.deletedAt == null && !it.isDraft }
        ?: throw FileNotFoundException("Unknown document $id")

internal fun scanPdfWriter(
    documents: DocumentDao,
    processing: PageProcessing,
    exporter: PdfExporter,
    files: FileStore,
): suspend (DocumentEntity, File) -> Long = { document, output ->
    val ids = documents.pages(document.id).map { it.id }
    require(ids.isNotEmpty()) { "Cannot open an empty scan" }
    processing.awaitIdle(ids)
    for (id in ids) {
        val page = files.pageWithSignatures(requireNotNull(documents.page(id)))
        if (page.processedHash != page.paramsHash() || page.processedPath?.let { File(it).isFile } != true) {
            processing.enqueueRender(id)
        }
    }
    processing.awaitIdle(ids)

    val revision = documents.readableDocument(document.id)
    val pages = documents.pages(document.id)
    check(pages.map { it.id } == ids) { "Scan pages changed during PDF export" }
    val sources = pages.map { row ->
        val page = files.pageWithSignatures(row)
        val source = page.processedPath?.let(::File)?.takeIf { it.isFile && page.processedHash == page.paramsHash() }
            ?: throw FileNotFoundException("Processed image unavailable for page ${page.id}")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.path, bounds)
        check(bounds.outWidth > 0 && bounds.outHeight > 0) { "Invalid processed image for page ${page.id}" }
        ExportPage(source, bounds.outWidth, bounds.outHeight)
    }
    output.outputStream().use {
        exporter.export(sources, PageSizeOption.AUTO, QualityPreset.HIGH, it, revision.title)
    }
    revision.updatedAt
}
