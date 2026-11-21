package dev.turjo.paperless.core.pdf

import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.turjo.paperless.core.model.PdfKey
import java.io.File
import java.io.IOException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class AndroidPdfSessionManagerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val directory = File(context.cacheDir, "reader-test").apply { mkdirs() }
    private val files = mutableMapOf<PdfKey, File>()
    private val manager = AndroidPdfSessionManager(context, { Uri.fromFile(files.getValue(it)) })

    @After
    fun tearDown() {
        files.keys.forEach(manager::release)
        directory.deleteRecursively()
    }

    @Test
    fun rendersAtRequestedWidthAndCachesDayAndNight() = runBlocking {
        val handle = manager.open(asset("multipage-15.pdf"))
        assertEquals(15, handle.pageCount)
        val size = handle.pageSize(0)
        val start = SystemClock.elapsedRealtimeNanos()
        val bitmap = handle.render(0, 1344)
        val elapsedMs = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
        Log.i("ReaderBenchmark", "Cold 1344 px render of multipage-15.pdf page 0: $elapsedMs ms")
        assertEquals(1344, bitmap.width)
        assertEquals((1344.0 * size.height / size.width).roundToInt(), bitmap.height)
        assertEquals(Color.WHITE, bitmap.getPixel(0, 0))
        assertSame(bitmap, handle.render(0, 1344))
        val night = handle.render(0, 1344, night = true)
        assertNotSame(bitmap, night)
        assertSame(night, handle.render(0, 1344, night = true))
        assertEquals(Color.BLACK, night.getPixel(0, 0))
    }

    @Test
    fun reportsRotatedSizes() = runBlocking {
        val handle = manager.open(asset("rotated-pages.pdf"))
        assertEquals(6, handle.pageCount)
        for (index in 0 until handle.pageCount) {
            val size = handle.pageSize(index)
            assertEquals(if (index % 2 == 0) 595f else 842f, size.width, 1f)
            assertEquals(if (index % 2 == 0) 842f else 595f, size.height, 1f)
            assertEquals((320.0 * size.height / size.width).roundToInt(), handle.render(index, 320).height)
        }
    }

    @Test
    fun concurrentRenderingAcrossThreeDocumentsKeepsAtMostTwoDescriptors() = runBlocking {
        val first = asset("multipage-15.pdf")
        val second = asset("rotated-pages.pdf")
        val third = asset("single-page.pdf")
        val keys = listOf(first, second, third)
        val handles = keys.map { manager.open(it) }
        assertEquals(2, keys.count { hasOpenDescriptor(files.getValue(it)) })
        assertTrue(!hasOpenDescriptor(files.getValue(first)))
        (0 until 36).map { request ->
            async {
                val handle = handles[request % handles.size]
                val width = 200 + request
                assertEquals(width, handle.render(request % handle.pageCount, width).width)
            }
        }.awaitAll()
        assertEquals(2, keys.count { hasOpenDescriptor(files.getValue(it)) })
    }

    @Test
    fun rendersSpreadOutPagesInLargePdf() = runBlocking {
        val handle = manager.open(asset("large-300.pdf"))
        assertEquals(300, handle.pageCount)
        for (page in listOf(0, 150, 299)) assertEquals(400, handle.render(page, 400).width)
    }

    @Test
    fun reopeningReplacedScanUsesItsNewPages() = runBlocking {
        val key = asset("multipage-15.pdf")
        val oldHandle = manager.open(key)
        manager.open(asset("rotated-pages.pdf"))
        val replacement = asset("single-page.pdf")
        manager.open(replacement)
        files.getValue(replacement).copyTo(files.getValue(key), overwrite = true)

        assertTrue(runCatching { oldHandle.pageSize(0) }.exceptionOrNull() is IOException)
        val refreshed = manager.open(key)
        assertEquals(1, refreshed.pageCount)
        assertEquals(320, refreshed.render(0, 320).width)
    }

    @Test
    fun reportsEncryptedAndCorruptFiles() = runBlocking {
        val encrypted = runCatching { manager.open(asset("encrypted-aes256.pdf")) }.exceptionOrNull()
        assertTrue("Expected encrypted, got $encrypted", encrypted is PdfOpenException.Encrypted)
        val corrupt = runCatching { manager.open(asset("batch-folder/broken.pdf")) }.exceptionOrNull()
        assertTrue("Expected corrupt, got $corrupt", corrupt is PdfOpenException.Corrupt)
    }

    private fun hasOpenDescriptor(file: File): Boolean =
        File("/proc/self/fd").listFiles().orEmpty().any { descriptor ->
            runCatching { android.system.Os.readlink(descriptor.path) == file.canonicalPath }.getOrDefault(false)
        }

    private fun asset(name: String): PdfKey {
        val file = File(directory, name.substringAfterLast('/'))
        context.assets.open(name).use { input -> file.outputStream().use { input.copyTo(it) } }
        return PdfKey(name).also { files[it] = file }
    }
}
