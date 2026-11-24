package dev.turjo.paperless.core.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.util.Log
import dev.turjo.paperless.core.imaging.BookSplitter
import dev.turjo.paperless.core.imaging.Geometry
import dev.turjo.paperless.core.imaging.IdCardComposer
import dev.turjo.paperless.core.imaging.IdCardLayout
import dev.turjo.paperless.core.imaging.InkColour
import dev.turjo.paperless.core.imaging.OpenCv
import dev.turjo.paperless.core.imaging.PageTools
import dev.turjo.paperless.core.imaging.ProcessParams
import dev.turjo.paperless.core.imaging.ProcessResult
import dev.turjo.paperless.core.imaging.detectOnFile
import dev.turjo.paperless.core.imaging.refineOnFile
import dev.turjo.paperless.core.model.Page
import dev.turjo.paperless.core.model.PageBlankInfo
import dev.turjo.paperless.core.model.PageFilter
import dev.turjo.paperless.core.model.PageMode
import dev.turjo.paperless.core.model.Quad
import dev.turjo.paperless.core.model.paramsHash
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import dev.turjo.paperless.core.imaging.PageProcessor as ImagingPageProcessor

data class PixelSize(val width: Int, val height: Int)

interface PageProcessing {
    val busyPageIds: StateFlow<Set<String>>
    fun enqueueNewPage(pageId: String, filter: PageFilter = PageFilter.DOCUMENT)
    fun enqueueNewPage(pageId: String, mode: PageMode, filter: PageFilter = PageFilter.DOCUMENT) =
        enqueueNewPage(pageId, filter)
    /**
     * [hint] is the quad the live camera overlay held steady at capture (normalized upright TL, TR, BR, BL). Plain
     * pages then only refine it instead of running the full search.
     */
    fun enqueueNewPage(pageId: String, mode: PageMode, filter: PageFilter, hint: FloatArray?) =
        enqueueNewPage(pageId, mode, filter)
    suspend fun updateIdLayout(pageId: String, layout: IdCardLayout): Boolean = false
    fun enqueueRender(pageId: String)
    suspend fun awaitIdle(pageIds: Collection<String>)
    suspend fun detectQuad(pageId: String): Quad?
    /** The book fold inside [quad] as (topX, topY, bottomX, bottomY) in upright pixels, or null if none is clear. */
    suspend fun findFold(pageId: String, quad: Quad): FloatArray? = null
    suspend fun originalBitmap(pageId: String, maxSide: Int): Bitmap
    suspend fun previewBitmap(page: Page, maxSide: Int): Bitmap
    suspend fun uprightSize(pageId: String): PixelSize?
    suspend fun blankInfo(pageIds: List<String>): List<PageBlankInfo> =
        throw UnsupportedOperationException("Blank-page detection unavailable")
    suspend fun skewAngle(page: Page): Double? = null
}

/**
 * Full-resolution work runs on one thread of an app-wide scope: pages keep processing after the screen that
 * asked for them closes, and at most one 12 MP page is in memory at a time (PRD §6). Previews use a cached
 * downsampled upright copy of the original so editor feedback stays fast.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class QueuedPageProcessing internal constructor(
    private val documents: DocumentRepository,
    private val files: FileStore,
    private val thumbnails: ThumbnailMaker,
    private val scope: CoroutineScope,
    private val renderPage: ((File, ProcessParams, File) -> ProcessResult)?,
) : PageProcessing {
    @Inject
    constructor(
        documents: DocumentRepository,
        files: FileStore,
        thumbnails: ThumbnailMaker,
        @ApplicationScope scope: CoroutineScope,
    ) : this(documents, files, thumbnails, scope, null)

    // Two pages at a time (a 12 MP page needs ~150 MB of native memory while it renders); one job per page at a time.
    private val worker = Dispatchers.Default.limitedParallelism(PARALLEL_PAGES)
    private val slots = Semaphore(PARALLEL_PAGES)
    private val pageLocks = ConcurrentHashMap<String, Mutex>()
    private val compositeLock = Mutex()
    private val previewLock = Mutex()
    private val processor = ImagingPageProcessor()
    private val pending = mutableMapOf<String, Int>()
    private val busy = MutableStateFlow<Set<String>>(emptySet())
    private val sizes = ConcurrentHashMap<String, PixelSize>()

    override val busyPageIds: StateFlow<Set<String>> = busy.asStateFlow()

    override fun enqueueNewPage(pageId: String, filter: PageFilter) =
        enqueueNewPage(pageId, PageMode.NORMAL, filter)

    override fun enqueueNewPage(pageId: String, mode: PageMode, filter: PageFilter) =
        enqueueNewPage(pageId, mode, filter, null)

    override fun enqueueNewPage(pageId: String, mode: PageMode, filter: PageFilter, hint: FloatArray?) = submit(pageId) {
        val page = documents.getPage(pageId) ?: return@submit
        val source = File(page.originalPath)
        val detectStart = System.nanoTime()
        val fromHint = hint?.takeIf { mode == PageMode.NORMAL || mode == PageMode.WHITEBOARD }?.let { start ->
            runCatching { refineOnFile(source, start) }
                .onFailure { Log.w(TAG, "Hint refinement failed for $pageId", it) }
                .getOrNull()
        }
        val detected = fromHint ?: runCatching { detectOnFile(source, aspectPrior = mode.cardAspect()).quad }
            .onFailure { Log.w(TAG, "Detection failed for $pageId", it) }
            .getOrNull()
        Log.d(TIMING, "detect ${(System.nanoTime() - detectStart) / 1_000_000} ms")
        val prepared = page.copy(mode = mode, quad = detected?.let(Quad::fromArray), filter = filter)
        if (mode == PageMode.BOOK_L) {
            val halves = detected?.let {
                runCatching { BookSplitter.split(source, it) }
                    .onFailure { error -> Log.w(TAG, "Book split failed for $pageId", error) }
                    .getOrNull()
            }
            if (halves != null) {
                val left = prepared.copy(quad = Quad.fromArray(halves.first))
                val right = documents.splitBookPage(left, Quad.fromArray(halves.second))
                try {
                    render(pageId)
                } finally {
                    render(right.id)
                }
                return@submit
            } else {
                documents.updatePage(prepared.copy(mode = PageMode.NORMAL))
            }
        } else {
            val glareMode = mode.cardAspect() != null || mode == PageMode.WHITEBOARD
            documents.updatePage(prepared.copy(
                filter = when {
                    mode == PageMode.WHITEBOARD -> PageFilter.WHITEBOARD
                    mode.cardAspect() != null -> PageFilter.AUTO
                    else -> filter
                },
                glareFix = glareMode || page.glareFix,
                deskew = !glareMode && page.deskew,
            ))
        }
        render(pageId)
        if (mode == PageMode.ID_BACK) createIdComposite(pageId)
    }

    private suspend fun createIdComposite(backId: String) {
        if (documents.getPage(idCompositeId(backId)) != null) return
        val back = documents.getPage(backId) ?: return
        val document = documents.observeDocument(back.documentId).first() ?: return
        val front = document.pages.getOrNull(back.index - 1)?.takeIf { it.mode == PageMode.ID_FRONT } ?: return
        composeIdCard(front, back, IdCardLayout()) { output ->
            documents.addIdComposite(front, back, output, IdCardComposer.PAGE_WIDTH, IdCardComposer.PAGE_HEIGHT)
            true
        }
    }

    override suspend fun updateIdLayout(pageId: String, layout: IdCardLayout): Boolean {
        markPending(pageId)
        try {
            return withContext(worker) {
                slots.withPermit {
                    val composite = documents.idComposite(pageId) ?: return@withPermit false
                    recomposeIdCard(composite.copy(layout = layout))
                }
            }
        } finally {
            markFinished(pageId)
        }
    }

    private suspend fun recomposeIdCard(composite: IdComposite): Boolean = compositeLock.withLock {
        recomposeIdCardLocked(composite)
    }

    private suspend fun recomposeIdCardLocked(composite: IdComposite): Boolean {
        val front = composite.frontId?.let { documents.getPage(it) } ?: return false
        val back = documents.getPage(composite.backId) ?: return false
        val page = documents.getPage(composite.pageId) ?: return false
        if (front.documentId != page.documentId || back.documentId != page.documentId) return false
        return composeIdCard(front, back, composite.layout) { documents.replaceIdComposite(composite, it) }
    }

    private suspend fun composeIdCard(
        front: Page, back: Page, layout: IdCardLayout, save: suspend (File) -> Boolean,
    ): Boolean {
        val frontFile = front.processedPath?.let(::File)?.takeIf(File::isFile) ?: return false
        val backFile = back.processedPath?.let(::File)?.takeIf(File::isFile) ?: return false
        val output = files.newTempFile(".jpg")
        try {
            IdCardComposer.compose(frontFile, backFile, output, layout)
            return save(output)
        } finally {
            output.delete()
        }
    }

    private fun PageMode.cardAspect(): Float? =
        if (this == PageMode.ID_FRONT || this == PageMode.ID_BACK) 1.586f else null

    override fun enqueueRender(pageId: String) = submit(pageId) { render(pageId) }

    override suspend fun awaitIdle(pageIds: Collection<String>) {
        val related = pageIds.toMutableSet()
        for (id in pageIds) {
            val composite = documents.idComposite(id) ?: continue
            composite.frontId?.let(related::add)
            related.add(composite.backId)
        }
        busy.first { current -> related.none(current::contains) }
    }

    override suspend fun detectQuad(pageId: String): Quad? = withContext(worker) {
        val page = documents.getPage(pageId) ?: return@withContext null
        detectOnFile(File(page.originalPath), aspectPrior = page.mode.cardAspect()).quad?.let(Quad::fromArray)
    }

    override suspend fun findFold(pageId: String, quad: Quad): FloatArray? = withContext(worker) {
        val page = documents.getPage(pageId) ?: return@withContext null
        val left = runCatching { BookSplitter.split(File(page.originalPath), quad.toArray()) }
            .onFailure { Log.w(TAG, "Fold search failed for $pageId", it) }
            .getOrNull()?.first ?: return@withContext null
        floatArrayOf(left[2], left[3], left[4], left[5])
    }

    override suspend fun originalBitmap(pageId: String, maxSide: Int): Bitmap = withContext(Dispatchers.IO) {
        val page = requireNotNull(documents.getPage(pageId)) { "No page $pageId" }
        decodeUpright(File(page.originalPath), maxSide)
    }

    override suspend fun previewBitmap(page: Page, maxSide: Int): Bitmap = previewLock.withLock {
        withContext(Dispatchers.Default) {
            val source = previewSource(page)
            val upright = uprightSizeOf(page)
            val scale = source.second.width.toFloat() / upright.width
            val out = files.newTempFile(".jpg")
            try {
                processor.process(source.first, page.toParams(scale), out, jpegQuality = 90)
                BitmapFactory.decodeFile(out.path, BitmapFactory.Options().apply {
                    inSampleSize = sampleSize(out, maxSide)
                }) ?: error("Preview decode failed for ${page.id}")
            } finally {
                out.delete()
            }
        }
    }

    override suspend fun uprightSize(pageId: String): PixelSize? = withContext(Dispatchers.IO) {
        documents.getPage(pageId)?.let(::uprightSizeOf)
    }

    private fun markPending(pageId: String) {
        synchronized(pending) {
            pending.merge(pageId, 1, Int::plus)
            busy.update { it + pageId }
        }
    }

    private fun markFinished(pageId: String) {
        synchronized(pending) {
            val left = pending.merge(pageId, -1, Int::plus) ?: 0
            if (left <= 0) {
                pending.remove(pageId)
                busy.update { it - pageId }
            }
        }
    }

    private fun submit(pageId: String, block: suspend () -> Unit) {
        markPending(pageId)
        scope.launch(worker) {
            try {
                pageLocks.getOrPut(pageId) { Mutex() }.withLock { slots.withPermit { block() } }
            } catch (error: Exception) {
                Log.e(TAG, "Processing failed for $pageId", error)
            } finally {
                markFinished(pageId)
            }
        }
    }

    private suspend fun render(pageId: String) {
        val page = documents.getPage(pageId) ?: return
        if (page.isIdComposite) return
        val hash = page.paramsHash()
        val processed = files.processedFile(pageId)
        if (page.processedHash == hash && page.processedPath?.let { File(it).isFile } == true) {
            refreshIdComposites(page)
            return
        }
        val original = File(page.originalPath)
        val params = page.toParams(1f)
        val result = renderPage?.invoke(original, params, processed) ?: processor.process(original, params, processed)
        val thumbStart = System.nanoTime()
        thumbnails.make(processed, pageId)
        Log.d(TIMING, "thumbnail ${(System.nanoTime() - thumbStart) / 1_000_000} ms")
        // Re-read: the user may have edited the page while it rendered; keep their params, record what we rendered.
        val latest = documents.getPage(pageId) ?: return
        documents.updatePage(
            latest.copy(processedPath = processed.path, processedHash = hash, glareWarning = result.glareWarning),
        )
        if (latest.paramsHash() != hash) render(pageId) else refreshIdComposites(latest)
    }

    private suspend fun refreshIdComposites(page: Page) {
        if (page.mode.cardAspect() != null) {
            documents.idCompositesFor(page).forEach { recomposeIdCard(it) }
        }
    }

    private fun Page.toParams(scale: Float) = ProcessParams(
        quad = quad?.toArray()?.let { q -> FloatArray(8) { q[it] * scale } },
        rotation = rotation,
        filter = filter.name,
        brightness = brightness,
        contrast = contrast,
        shadowFix = shadowFix,
        glareFix = glareFix,
        snapToPaper = mode.cardAspect() == null,
        deskew = deskew,
        inkRemoval = inkRemoval?.name,
    )

    /** An upright (EXIF applied, tag removed) copy of the original, long side ≤ PREVIEW_SOURCE_SIDE. */
    private fun previewSource(page: Page): Pair<File, PixelSize> {
        val file = File(files.pageDir(page.id), "preview-src.jpg")
        if (!file.isFile) {
            val bitmap = decodeUpright(File(page.originalPath), PREVIEW_SOURCE_SIDE)
            val temp = File(file.parentFile, "preview-src.tmp")
            FileOutputStream(temp).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            bitmap.recycle()
            check(temp.renameTo(file)) { "Could not write $file" }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        return file to PixelSize(bounds.outWidth, bounds.outHeight)
    }

    private fun uprightSizeOf(page: Page): PixelSize = sizes.getOrPut(page.id) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(page.originalPath, bounds)
        if (isTransposed(orientation(File(page.originalPath)))) PixelSize(bounds.outHeight, bounds.outWidth)
        else PixelSize(bounds.outWidth, bounds.outHeight)
    }

    private fun decodeUpright(file: File, maxSide: Int): Bitmap {
        val decoded = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply {
            inSampleSize = sampleSize(file, maxSide)
        }) ?: error("Cannot decode $file")
        val o = orientation(file)
        val matrix = Matrix().apply {
            when (o) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> postScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> postScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { postRotate(90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> { postRotate(-90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(-90f)
            }
        }
        val longSide = maxOf(decoded.width, decoded.height)
        if (longSide > maxSide) matrix.postScale(maxSide.toFloat() / longSide, maxSide.toFloat() / longSide)
        if (matrix.isIdentity) return decoded
        return Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
            .also { if (it !== decoded) decoded.recycle() }
    }

    private fun sampleSize(file: File, maxSide: Int): Int {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        return sample
    }

    private fun orientation(file: File) =
        ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)

    private fun isTransposed(o: Int) = o in ExifInterface.ORIENTATION_TRANSPOSE..ExifInterface.ORIENTATION_ROTATE_270

    private companion object {
        const val TAG = "PageProcessing"
        const val TIMING = "ScanTiming"
        const val PARALLEL_PAGES = 2
        const val PREVIEW_SOURCE_SIDE = 1600
    }

    override suspend fun blankInfo(pageIds: List<String>): List<PageBlankInfo> = withContext(worker) {
        awaitIdle(pageIds)
        OpenCv.ensureLoaded()
        pageIds.map { id ->
            val page = documents.getPage(id)
            val file = page?.processedPath?.let(::File)?.takeIf(File::isFile)
            if (file == null || page.processedHash != page.paramsHash()) return@map PageBlankInfo(id, false)
            val bitmap = decodeUpright(file, 800)
            val image = Mat()
            try {
                Utils.bitmapToMat(bitmap, image)
                Imgproc.cvtColor(image, image, Imgproc.COLOR_RGBA2BGR)
                PageBlankInfo(id, PageTools.inkFraction(image) < 0.001)
            } finally {
                image.release()
                bitmap.recycle()
            }
        }
    }

    override suspend fun skewAngle(page: Page): Double? = previewLock.withLock {
        withContext(Dispatchers.Default) {
            OpenCv.ensureLoaded()
            val source = previewSource(page)
            val scale = source.second.width.toFloat() / uprightSizeOf(page).width
            var image = Imgcodecs.imread(source.first.path, Imgcodecs.IMREAD_COLOR)
            try {
                if (image.empty()) return@withContext null
                page.toParams(scale).quad?.let { quad ->
                    val warped = Geometry.warp(image, quad)
                    image.release()
                    image = warped
                }
                page.inkRemoval?.let { ink ->
                    val cleaned = PageTools.removeInk(image, InkColour.valueOf(ink.name))
                    image.release()
                    image = cleaned
                }
                val (angle, confidence) = PageTools.estimateSkew(image)
                angle.takeIf { confidence >= 0.25 && kotlin.math.abs(it) >= 0.3 }
            } finally {
                image.release()
            }
        }
    }
}
