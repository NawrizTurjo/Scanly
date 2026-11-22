package dev.turjo.paperless.feature.tools

import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.file.Files
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImagesToPdfTest {
    @Test fun documentFitsPaperAndOffPreservesPhotoProportions() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = Files.createTempDirectory(context.cacheDir.toPath(), "images-to-pdf-").toFile()
        try {
            val inputs = listOf("skewed_02.jpg", "busy-background_01.jpg").map { name ->
                val file = File(directory, name)
                instrumentation.context.assets.open(name).use { input ->
                    file.outputStream().use { input.copyTo(it) }
                }
                Input(file, name)
            }
            val progress = mutableListOf<Pair<Int, Int>>()
            val ops = PdfToolOps(context) { position, total -> progress += position to total }
            val request = ToolRequest(Tool.FROM_IMAGES, inputs)
            val enhanced = ops.run(request, File(directory, "enhanced").apply { mkdir() }).single()
            assertEquals(listOf(1 to 2, 2 to 2), progress)
            val enhancedAspects = pageAspects(enhanced.file)

            progress.clear()
            val off = ops.run(
                request.copy(options = mapOf(ENHANCE_OPTION to ImageEnhancement.OFF.name)),
                File(directory, "off").apply { mkdir() },
            ).single()
            assertTrue(progress.isEmpty())
            val rawAspects = pageAspects(off.file)

            assertEquals(2, enhancedAspects.size)
            assertEquals(2, rawAspects.size)
            val paperAspects = listOf(210f / 297f, 8.5f / 11f)
            inputs.forEachIndexed { index, input ->
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(input.file.path, bounds)
                val photoAspect = bounds.outWidth.toFloat() / bounds.outHeight
                val enhancedAspect = enhancedAspects[index]
                assertEquals(input.name, photoAspect, rawAspects[index], 0.003f)
                assertTrue("${input.name} enhanced aspect was $enhancedAspect",
                    paperAspects.any { abs(enhancedAspect - it) <= it * 0.10f })
                assertTrue(input.name, abs(enhancedAspect - photoAspect) > 0.02f)
            }
            assertEquals(listOf(enhanced.file), enhanced.file.parentFile!!.listFiles()!!.toList())
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun pageAspects(file: File): List<Float> =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                List(renderer.pageCount) { index ->
                    renderer.openPage(index).use { page -> page.width.toFloat() / page.height }
                }
            }
        }
}
