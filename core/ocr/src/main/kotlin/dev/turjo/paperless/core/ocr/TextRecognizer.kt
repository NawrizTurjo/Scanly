package dev.turjo.paperless.core.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.os.Process
import android.util.AtomicFile
import com.googlecode.tesseract.android.TessBaseAPI
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class RecognizedWord(
    val text: String,
    val confidence: Float,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

data class RecognizedText(val text: String, val words: List<RecognizedWord>)

fun interface TextRecognizer {
    suspend fun recognize(jpeg: File, language: String): RecognizedText
}

@Module
@InstallIn(SingletonComponent::class)
internal interface OcrModule {
    @Binds fun bindRecognizer(recognizer: TesseractRecognizer): TextRecognizer
}

@Singleton
class TesseractRecognizer @Inject constructor(
    @ApplicationContext private val context: Context,
) : TextRecognizer {
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            task.run()
        }, "Scanly OCR")
    }.asCoroutineDispatcher()
    private var engine: TessBaseAPI? = null
    private var activeLanguage: String? = null
    private val memoryGeneration = AtomicInteger()
    private var recognitionGeneration = 0

    override suspend fun recognize(jpeg: File, language: String): RecognizedText = withContext(worker) {
        val generation = memoryGeneration.get()
        require(language in setOf("eng", "ben", "eng+ben"))
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val decoded = jpeg.inputStream().use { input ->
            BitmapFactory.decodeFileDescriptor(input.fd, null, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Cannot read OCR image" }
            val longSide = maxOf(bounds.outWidth, bounds.outHeight)
            val options = BitmapFactory.Options().apply {
                inSampleSize = 1
                while (longSide / inSampleSize / 2 >= 2500) inSampleSize *= 2
            }
            requireNotNull(BitmapFactory.decodeFileDescriptor(input.fd, null, options))
        }
        val longSide = maxOf(bounds.outWidth, bounds.outHeight)
        val scale = minOf(1f, 2500f / longSide)
        val grayscale = try {
            Bitmap.createBitmap(
                (bounds.outWidth * scale).roundToInt().coerceAtLeast(1),
                (bounds.outHeight * scale).roundToInt().coerceAtLeast(1),
                Bitmap.Config.ARGB_8888,
            ).also { bitmap ->
                val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
                    colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
                }
                Canvas(bitmap).drawBitmap(decoded, null, Rect(0, 0, bitmap.width, bitmap.height), paint)
            }
        } finally {
            decoded.recycle()
        }
        try {
            ensureActive()
            val api = engineFor(language)
            try {
                api.setImage(grayscale)
                checkMemory(generation)
                recognitionGeneration = generation
                // The wrapper only supplies a cancellation monitor for its hOCR call.
                api.getHOCRText(0)
                checkMemory(generation)
                val text = api.getUTF8Text().orEmpty().trim()
                ensureActive()
                val level = TessBaseAPI.PageIteratorLevel.RIL_WORD
                val sx = bounds.outWidth.toFloat() / grayscale.width
                val sy = bounds.outHeight.toFloat() / grayscale.height
                val words = buildList {
                    val iterator = api.resultIterator ?: return@buildList
                    try {
                        iterator.begin()
                        do {
                            val word = iterator.getUTF8Text(level)
                            val box = iterator.getBoundingBox(level)
                            if (!word.isNullOrBlank() && box != null) {
                                add(RecognizedWord(word, iterator.confidence(level),
                                    box[0] * sx, box[1] * sy, box[2] * sx, box[3] * sy))
                            }
                        } while (iterator.next(level))
                    } finally {
                        iterator.delete()
                    }
                }
                RecognizedText(text, words)
            } finally {
                api.clear()
            }
        } finally {
            grayscale.recycle()
        }
    }

    fun stopForMemoryPressure() {
        memoryGeneration.incrementAndGet()
    }

    suspend fun release() = withContext(worker) {
        engine?.recycle()
        engine = null
        activeLanguage = null
    }

    private fun checkMemory(generation: Int) {
        if (generation != memoryGeneration.get()) throw IOException("OCR interrupted by memory pressure")
    }

    private fun engineFor(language: String): TessBaseAPI {
        val api = engine ?: TessBaseAPI {
            if (recognitionGeneration != memoryGeneration.get()) engine?.stop()
        }.also { engine = it }
        if (activeLanguage != language) {
            activeLanguage = null
            val data = File(context.filesDir, "tessdata").apply { mkdirs() }
            val version = File(data, "version")
            val languages = listOf("eng", "ben")
            if (!version.isFile || version.readText() != MODEL_VERSION ||
                languages.any { !File(data, "$it.traineddata").isFile }
            ) {
                for (code in languages) {
                    val name = "$code.traineddata"
                    val target = AtomicFile(File(data, name))
                    val output = target.startWrite()
                    try {
                        context.assets.open("tessdata/$name").use { it.copyTo(output) }
                        target.finishWrite(output)
                    } catch (error: Throwable) {
                        target.failWrite(output)
                        throw error
                    }
                }
                version.writeText(MODEL_VERSION)
            }
            check(api.init(context.filesDir.path, language, TessBaseAPI.OEM_LSTM_ONLY)) {
                "Could not initialize OCR for $language"
            }
            activeLanguage = language
        }
        return api
    }

    private companion object {
        const val MODEL_VERSION = "tessdata_fast-4.1.0"
    }
}
