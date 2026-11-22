package dev.turjo.paperless.feature.reader

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.turjo.paperless.core.data.PdfSaveService
import dev.turjo.paperless.core.data.ReaderRepository
import dev.turjo.paperless.core.data.SaveResult
import dev.turjo.paperless.core.model.OutlineNode
import dev.turjo.paperless.core.model.PdfKey
import dev.turjo.paperless.core.pdf.ChapterSuggester
import dev.turjo.paperless.core.pdf.Outlines
import dev.turjo.paperless.core.pdf.PdfBox
import java.io.File
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ReaderOutlineFiles internal constructor(
    private val source: suspend (PdfKey) -> Source,
    private val output: () -> File,
    private val write: suspend (File, List<OutlineNode>, File) -> Unit,
    private val saves: PdfSaveService,
) {
    internal data class Source(val file: File, val temporary: Boolean = false)

    @Inject
    constructor(
        @ApplicationContext context: Context,
        repository: ReaderRepository,
        saves: PdfSaveService,
    ) : this(
        { key -> readerSource(context, repository, key) },
        { File.createTempFile("reader-outline-", ".pdf", context.cacheDir) },
        Outlines::write,
        saves,
    )

    internal suspend fun read(key: PdfKey) = withSource(key) { Outlines.read(it) }

    internal suspend fun suggest(key: PdfKey) = withSource(key) { ChapterSuggester.suggest(it) }

    internal suspend fun save(key: PdfKey, outline: List<OutlineNode>, overwrite: Boolean): SaveResult =
        withContext(Dispatchers.IO) {
            val destination = output()
            try {
                withSource(key) { write(it, outline, destination) }
                if (overwrite) saves.replaceExternal(key, destination) else saves.saveCopy(key, destination)
            } finally {
                destination.delete()
            }
        }

    private suspend fun <T> withSource(key: PdfKey, action: suspend (File) -> T): T = withContext(Dispatchers.IO) {
        val input = source(key)
        try {
            action(input.file)
        } finally {
            if (input.temporary) input.file.delete()
        }
    }
}

// The reader repository owns both SAF grants and library PDF materialization.
internal suspend fun readerSource(context: Context, repository: ReaderRepository, key: PdfKey): ReaderOutlineFiles.Source {
    PdfBox.ensureInit(context)
    val uri = repository.resolveUri(key)
    if (uri.scheme == "file") return ReaderOutlineFiles.Source(File(requireNotNull(uri.path)))
    val file = File.createTempFile("reader-source-", ".pdf", context.cacheDir)
    try {
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot read PDF")
        input.use { source -> file.outputStream().use { source.copyTo(it) } }
        return ReaderOutlineFiles.Source(file, temporary = true)
    } catch (error: Exception) {
        file.delete()
        throw error
    }
}
