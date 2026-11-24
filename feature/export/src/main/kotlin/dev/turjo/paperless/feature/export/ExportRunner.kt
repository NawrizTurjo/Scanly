package dev.turjo.paperless.feature.export

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.turjo.paperless.core.data.DocumentRepository
import dev.turjo.paperless.core.data.PageProcessing
import dev.turjo.paperless.core.model.DocumentWithPages
import dev.turjo.paperless.core.model.Page
import dev.turjo.paperless.core.model.paramsHash
import dev.turjo.paperless.core.pdf.*
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal enum class ExportFormat(val pdf: Boolean, val imageMime: String?) {
    PDF(true, null), JPG(false, "image/jpeg"), BOTH(true, "image/jpeg"), PNG(false, "image/png");

    val shareMime get() = if (this == BOTH) "*/*" else imageMime ?: PDF_MIME
}

internal const val PDF_MIME = "application/pdf"

internal data class ExportOptions(
    val format: ExportFormat = ExportFormat.PDF,
    val size: PageSizeOption = PageSizeOption.A4,
    val quality: QualityPreset = QualityPreset.MEDIUM,
    val pipeline: PdfPipeline = PdfPipeline(),
) {
    val canExport get() = !format.pdf || pipeline.password != ""
}

internal data class SavedExport(val uri: Uri, val mime: String, val format: ExportFormat)

@Singleton
class ExportRunner internal constructor(
    private val documents: DocumentRepository,
    private val processing: PageProcessing,
    private val cacheDir: () -> File,
    private val imageBounds: (File) -> Pair<Int, Int>,
    private val recompress: (File, Int?, Int, File) -> Unit,
    private val writePdf: suspend (List<ExportPage>, ExportOptions, File, String) -> Unit,
    private val writeImages: suspend (List<ExportPage>, ExportOptions, File, String) -> List<File>,
    private val saveFile: suspend (File, String) -> Uri,
    private val pipelineOps: PipelineOps,
) {
    @Inject
    constructor(
        documents: DocumentRepository,
        processing: PageProcessing,
        @ApplicationContext context: Context,
    ) : this(
        documents,
        processing,
        { exportCacheDir(context) },
        { file ->
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            bounds.outWidth to bounds.outHeight
        },
        JpegRecompressor::recompress,
        { pages, options, output, title ->
            output.outputStream().use {
                PdfExporter(context).export(pages, options.size, options.quality, it, title)
            }
        },
        { pages, options, dir, name ->
            val exporter = ImageExporter(context)
            if (options.format == ExportFormat.PNG) {
                exporter.exportPngs(pages, options.quality, dir, name)
            } else {
                exporter.exportJpegs(pages, options.quality, dir, name)
            }
        },
        { file, mime ->
            val saver = MediaStoreSaver(context)
            if (mime == PDF_MIME) saver.savePdf(file, file.name) else saver.saveImage(file, file.name, mime)
        },
        PdfPipelineOps(context),
    )

    private data class EstimateKey(
        val id: String,
        val quality: QualityPreset,
        val sources: List<Triple<File, Long, Long>>,
    )
    private val estimates = LinkedHashMap<EstimateKey, Long>()

    suspend fun quickShare(
        document: DocumentWithPages,
        launch: (List<File>, String) -> Unit,
    ) = share(document, ExportOptions(pipeline = PdfPipeline(emptyList())), document.document.title, launch)

    internal suspend fun share(
        document: DocumentWithPages,
        options: ExportOptions,
        name: String,
        launch: (List<File>, String) -> Unit,
    ) {
        // Mark ownership only after the chooser successfully receives the file grants.
        var handedOff = false
        export(document, options, name, { handedOff }) { files ->
            withContext(Dispatchers.Main.immediate) {
                launch(files, options.format.shareMime)
                handedOff = true
            }
        }
    }

    internal suspend fun save(
        document: DocumentWithPages,
        options: ExportOptions,
        name: String,
    ): SavedExport = export(document, options, name) { files ->
        var first: SavedExport? = null
        for (file in files) {
            val mime = if (file.extension == "pdf") PDF_MIME else requireNotNull(options.format.imageMime)
            val saved = SavedExport(saveFile(file, mime), mime, options.format)
            if (first == null) first = saved
        }
        requireNotNull(first)
    }

    private suspend fun <T> export(
        document: DocumentWithPages,
        options: ExportOptions,
        name: String,
        retainFiles: () -> Boolean = { false },
        consume: suspend (List<File>) -> T,
    ): T = withContext(Dispatchers.IO) {
        val ids = document.pages.sortedBy(Page::index).map(Page::id)
        require(ids.isNotEmpty())
        processing.awaitIdle(ids)
        for (id in ids) {
            val page = requireNotNull(documents.getPage(id))
            if (page.processedHash != page.paramsHash()) processing.enqueueRender(id)
        }
        processing.awaitIdle(ids)
        val pages = ids.map { id ->
            val page = requireNotNull(documents.getPage(id))
            val source = sourceFile(page)
            val (width, height) = imageBounds(source)
            require(width > 0 && height > 0) { "Invalid image dimensions for $id" }
            ExportPage(source, width, height)
        }

        val dir = Files.createTempDirectory(cacheDir().toPath(), "export-").toFile()
        try {
            val baseName = sanitizeExportName(name)
            val outputs = buildList {
                if (options.format.pdf) {
                    val pdf = File(dir, "$baseName.pdf")
                    writePdf(pages, options, pdf, document.document.title)
                    val result = applyPipeline(pdf, options.pipeline, dir, pipelineOps)
                    if (result != pdf) {
                        Files.move(result.toPath(), pdf.toPath(), REPLACE_EXISTING)
                    }
                    add(pdf)
                }
                if (options.format.imageMime != null) addAll(writeImages(pages, options, dir, baseName))
            }
            ensureActive()
            consume(outputs)
        } finally {
            if (!retainFiles()) dir.deleteRecursively()
        }
    }

    internal suspend fun estimate(document: DocumentWithPages, quality: QualityPreset): Long =
        withContext(Dispatchers.IO) {
            val sources = document.pages.sortedBy(Page::index).map(::sourceFile)
            val sizes = sources.map(File::length)
            val total = sizes.sum()
            val firstSize = sizes.firstOrNull()?.takeIf { it > 0 } ?: return@withContext total
            val jpegQuality = quality.jpegQuality ?: return@withContext total

            val key = EstimateKey(document.document.id, quality, sources.zip(sizes) { file, size ->
                Triple(file, size, file.lastModified())
            })
            synchronized(estimates) { estimates[key] }?.let { return@withContext it }

            val temp = File.createTempFile("estimate-", ".jpg", cacheDir())
            val bytes = try {
                recompress(sources.first(), quality.maxLongSide, jpegQuality, temp)
                (total * (temp.length().toDouble() / firstSize)).toLong()
            } finally {
                temp.delete()
            }
            synchronized(estimates) {
                estimates[key] = bytes
                if (estimates.size > 32) estimates.remove(estimates.keys.first())
            }
            bytes
        }

    private fun sourceFile(page: Page): File =
        page.processedPath?.let(::File)?.takeIf(File::isFile) ?: File(page.originalPath)
}

internal fun sanitizeExportName(name: String): String {
    val clean = name.map { char ->
        if (char.isISOControl() || char in "<>:\"/\\|?*") '_' else char
    }.joinToString("").trim { it.isWhitespace() || it == '.' }
        .take(60).trimEnd(' ', '.').ifBlank { "Scan" }
    val stem = clean.substringBefore('.').uppercase()
    val reserved = setOf("CON", "PRN", "AUX", "NUL") +
        (1..9).flatMap { listOf("COM$it", "LPT$it") }
    return if (stem in reserved) "_$clean" else clean
}
