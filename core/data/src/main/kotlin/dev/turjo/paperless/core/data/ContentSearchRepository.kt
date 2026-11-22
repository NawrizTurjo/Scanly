package dev.turjo.paperless.core.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import android.provider.DocumentsContract
import androidx.room.withTransaction
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.turjo.paperless.core.model.PdfKey
import dev.turjo.paperless.core.pdf.PdfBox
import java.io.File
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

data class ContentHit(val pdfKey: PdfKey, val title: String, val pageIndex: Int, val snippet: String)
data class IndexCounts(val pdfs: Int, val pages: Int)

class ContentSearchRepository @Inject constructor(private val database: PaperlessDatabase) {
    fun observeCounts() = database.pageTextDao().observeCounts()

    suspend fun search(query: String): List<ContentHit> {
        val match = contentMatch(query) ?: return emptyList()
        return database.pageTextDao().searchLibrary(match).map {
            ContentHit(PdfKey(it.pdfKey), it.title, it.pageIndex, it.snippet)
        }
    }
}

data class TitledTextHit(val pdfKey: String, val title: String, val pageIndex: Int, val snippet: String)

private val searchTerm = Regex("[\\p{L}\\p{M}\\p{N}]+")

internal fun contentMatch(query: String): String? {
    val terms = searchTerm.findAll(query).map { it.value }.toList()
    if (terms.isEmpty()) return null
    // FTS4 treats the asterisk inside the quoted phrase as a prefix marker.
    return terms.mapIndexed { index, term ->
        if (index == terms.lastIndex) "\"$term*\"" else "\"$term\""
    }.joinToString(" ")
}

class TextIndexer @Inject constructor(
    private val database: PaperlessDatabase,
    @ApplicationContext private val context: Context,
) {
    private val dao = database.pageTextDao()

    internal suspend fun indexLibrary(rebuild: Boolean): Int = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val readerCache = File(context.cacheDir, "pdf")
        val sources = database.readerDao().externalPdfs().map {
            PdfKey(it.id) to it.uri.toUri()
        } + database.documentDao().observeLibrary(null, false).first().mapNotNull {
            val pdf = restoredReaderPdf(context, it.id, it.updatedAt) ?: File(readerCache, "${it.id}.pdf")
            if (pdf.isFile && (pdf.parentFile != readerCache || pdf.lastModified() >= it.updatedAt)) {
                PdfKey("doc:${it.id}") to Uri.fromFile(pdf)
            } else null
        }

        val keys = sources.mapTo(hashSetOf()) { it.first.value }
        for (key in dao.indexedKeys() - keys) discard(key)

        var failed = 0
        for ((key, uri) in sources) {
            currentCoroutineContext().ensureActive()
            try {
                index(key, uri, revision(resolver, uri), rebuild)
            } catch (_: IOException) {
                discard(key.value)
                failed++
            } catch (_: SecurityException) {
                discard(key.value)
                failed++
            }
        }
        failed
    }

    internal suspend fun index(key: PdfKey, uri: Uri, modifiedAt: Long?, force: Boolean = false): Boolean =
        withContext(Dispatchers.IO) {
            if (!force && modifiedAt != null && dao.indexed(key.value)?.modifiedAt == modifiedAt) {
                return@withContext false
            }
            PdfBox.ensureInit(context)
            val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot read PDF")
            val memory = MemoryUsageSetting.setupTempFileOnly().setTempDir(context.cacheDir)
            input.use { stream ->
                PDDocument.load(stream, memory).use { pdf ->
                    val stripper = PDFTextStripper()
                    val rows = buildList {
                        for (page in 1..pdf.numberOfPages) {
                            currentCoroutineContext().ensureActive()
                            stripper.startPage = page
                            stripper.endPage = page
                            val text = stripper.getText(pdf).trim()
                            if (text.isNotEmpty()) add(PageTextEntity(key.value, page - 1, text))
                        }
                    }
                    database.withTransaction {
                        dao.deleteFor(key.value)
                        if (rows.isNotEmpty()) dao.insert(rows)
                        dao.markIndexed(IndexedPdfEntity(key.value, modifiedAt ?: -1L, pdf.numberOfPages, rows.isNotEmpty()))
                    }
                }
            }
            true
        }

    private suspend fun discard(key: String) = database.withTransaction {
        dao.deleteFor(key)
        dao.forget(key)
    }

    private fun revision(resolver: ContentResolver, uri: Uri): Long? {
        if (uri.scheme == ContentResolver.SCHEME_FILE) {
            return File(requireNotNull(uri.path)).lastModified().takeIf { it > 0 }
        }
        return try {
            resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED),
                null, null, null)?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) {
                    cursor.getLong(0).takeIf { it > 0 }
                } else null
            }
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}

