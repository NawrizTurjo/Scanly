package dev.turjo.paperless.core.pdf

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import android.util.LruCache
import android.util.SizeF
import dev.turjo.paperless.core.model.PdfKey
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

class AndroidPdfSessionManager(
    context: Context,
    private val uriResolver: suspend (PdfKey) -> Uri,
    maxOpen: Int = 2,
) : PdfSessionManager {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pool = PdfSessionPool(maxOpen, ::createRenderer, Renderer::close)
    private val cache = object : LruCache<String, Bitmap>(cacheBytes(appContext)) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    override suspend fun open(key: PdfKey): PdfDocumentHandle = withContext(Dispatchers.IO) {
        val uri = uriResolver(key)
        val file = if (uri.scheme == "file") File(requireNotNull(uri.path)) else null
        val source = Source(key, uri, file?.lastModified(), file?.length())
        val count = pool.use(source) { it.pageCount }
        object : PdfDocumentHandle {
            override val pageCount = count

            override suspend fun pageSize(index: Int): SizeF =
                pool.use(source) { handle ->
                    handle.page(index) { SizeF(it.width.toFloat(), it.height.toFloat()) }
                }

            override suspend fun render(index: Int, widthPx: Int, night: Boolean): Bitmap =
                pool.use(source) { handle ->
                    handle.onRenderer {
                        require(index in 0 until pageCount && widthPx > 0)
                        val prefix = "${source.cachePrefix}$index/$widthPx/"
                        cache.get("$prefix$night") ?: run {
                            val day = cache.get("${prefix}false") ?: drawPage(it, index, widthPx).also {
                                cache.put("${prefix}false", it)
                            }
                            if (!night) day else invert(day).also { cache.put("${prefix}true", it) }
                        }
                    }
                }
        }
    }

    override fun release(key: PdfKey) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            pool.releaseWhere { it.key == key }
        }
    }

    private suspend fun createRenderer(source: Source): Renderer {
        val dispatcher = Executors.newSingleThreadExecutor { Thread(it, "pdf-renderer") }.asCoroutineDispatcher()
        try {
            return withContext(dispatcher) {
                val descriptor = when (source.uri.scheme) {
                    "content" -> appContext.contentResolver.openFileDescriptor(source.uri, "r")
                        ?: throw IOException("PDF provider returned no descriptor")
                    "file" -> openFile(source)
                    else -> throw IOException("Unsupported PDF URI scheme")
                }
                val renderer = try {
                    PdfRenderer(descriptor)
                } catch (error: Throwable) {
                    try {
                        descriptor.close()
                    } catch (closeError: IOException) {
                        error.addSuppressed(closeError)
                    }
                    throw when (error) {
                        is SecurityException -> PdfOpenException.Encrypted(error)
                        is IOException, is IllegalArgumentException -> PdfOpenException.Corrupt(error)
                        else -> error
                    }
                }
                // PdfRenderer owns the descriptor after successful construction.
                Renderer(renderer, dispatcher)
            }
        } catch (error: Throwable) {
            dispatcher.close()
            throw error
        }
    }

    private fun openFile(source: Source): ParcelFileDescriptor {
        val file = File(requireNotNull(source.uri.path))
        val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        // An evicted handle can reopen, but only while its page count and cached images still match.
        if (file.lastModified() != source.modified || file.length() != source.length) {
            descriptor.close()
            throw IOException("PDF changed. Open the document again.")
        }
        return descriptor
    }

    private data class Source(val key: PdfKey, val uri: Uri, val modified: Long?, val length: Long?) {
        val cachePrefix = "${key.value.length}:${key.value}${uri.toString().length}:$uri/$modified/$length/"
    }

    private class Renderer(
        private val renderer: PdfRenderer,
        private val dispatcher: ExecutorCoroutineDispatcher,
    ) {
        val pageCount = renderer.pageCount

        suspend fun <T> onRenderer(block: (PdfRenderer) -> T): T =
            withContext(dispatcher) { block(renderer) }

        suspend fun <T> page(index: Int, block: (PdfRenderer.Page) -> T): T =
            onRenderer { it.openPage(index).use(block) }

        suspend fun close() {
            try {
                onRenderer { it.close() }
            } catch (error: IOException) {
                Log.w("PdfSessionManager", "Could not close PDF", error)
            } finally {
                dispatcher.close()
            }
        }
    }

    private companion object {
        fun cacheBytes(context: Context): Int =
            (context.getSystemService(ActivityManager::class.java).memoryClass * (1024L * 1024 / 8))
                .coerceIn(1, Int.MAX_VALUE.toLong()).toInt()

        fun drawPage(renderer: PdfRenderer, index: Int, width: Int): Bitmap = renderer.openPage(index).use { page ->
            val height = (width.toDouble() * page.height / page.width).roundToInt().coerceAtLeast(1)
            val scale = width.toFloat() / page.width
            val transform = Matrix().apply { setScale(scale, scale) }
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.WHITE)
                page.render(this, null, transform, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            }
        }

        fun invert(day: Bitmap): Bitmap {
            val paint = Paint().apply {
                colorFilter = ColorMatrixColorFilter(floatArrayOf(
                    -1f, 0f, 0f, 0f, 255f,
                    0f, -1f, 0f, 0f, 255f,
                    0f, 0f, -1f, 0f, 255f,
                    0f, 0f, 0f, 1f, 0f,
                ))
            }
            return Bitmap.createBitmap(day.width, day.height, Bitmap.Config.ARGB_8888).apply {
                Canvas(this).drawBitmap(day, 0f, 0f, paint)
            }
        }
    }
}
