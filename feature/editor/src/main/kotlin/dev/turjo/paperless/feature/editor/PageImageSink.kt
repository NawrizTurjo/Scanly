package dev.turjo.paperless.feature.editor

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.turjo.paperless.core.pdf.MediaStoreSaver
import dev.turjo.paperless.core.pdf.exportCacheDir
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Where a single enhanced page goes from the viewer: a cache copy for the share sheet, or Pictures/Scanly. */
internal interface PageImageSink {
    /** Copies [rendered] into the share cache as [name] and returns the copy. */
    suspend fun shareCopy(rendered: File, name: String): File

    suspend fun saveToPictures(rendered: File, name: String)
}

internal class AndroidPageImageSink @Inject constructor(
    @ApplicationContext private val context: Context,
) : PageImageSink {
    override suspend fun shareCopy(rendered: File, name: String): File = withContext(Dispatchers.IO) {
        File(exportCacheDir(context), name).also { rendered.copyTo(it, overwrite = true) }
    }

    override suspend fun saveToPictures(rendered: File, name: String) {
        MediaStoreSaver(context).saveImage(rendered, name, "image/jpeg")
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class PageImageSinkModule {
    @Binds
    abstract fun bind(sink: AndroidPageImageSink): PageImageSink
}
