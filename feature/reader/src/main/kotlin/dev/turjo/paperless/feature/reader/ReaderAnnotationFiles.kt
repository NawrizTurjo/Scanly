package dev.turjo.paperless.feature.reader

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.turjo.paperless.core.data.PdfSaveService
import dev.turjo.paperless.core.data.ReaderRepository
import dev.turjo.paperless.core.model.Annotation
import dev.turjo.paperless.core.model.PdfKey
import dev.turjo.paperless.core.pdf.AnnotationFlattener
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ReaderAnnotationFiles @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: ReaderRepository,
    private val saves: PdfSaveService,
) {
    internal suspend fun save(key: PdfKey, marks: List<Annotation>) = withContext(Dispatchers.IO) {
        val input = readerSource(context, repository, key)
        try {
            val output = File.createTempFile("reader-annotations-", ".pdf", context.cacheDir)
            try {
                AnnotationFlattener.flatten(input.file, marks, output)
                saves.saveCopy(key, output)
            } finally {
                output.delete()
            }
        } finally {
            if (input.temporary) input.file.delete()
        }
    }
}
