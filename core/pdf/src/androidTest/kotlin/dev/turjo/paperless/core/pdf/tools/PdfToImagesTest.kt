package dev.turjo.paperless.core.pdf.tools

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PdfToImagesTest {
    @Test
    fun rendersBothFormatsAtRequestedDpi() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dir = Files.createTempDirectory(context.cacheDir.toPath(), "pdf-to-images").toFile()
        try {
            val input = File(dir, "rotated-pages.pdf")
            context.assets.open(input.name).use { source -> input.outputStream().use { source.copyTo(it) } }
            val jpeg = PdfToImages.convert(input, 72, ImageFormat.JPEG, dir)
            val png = PdfToImages.convert(input, 144, ImageFormat.PNG, dir)
            assertEquals(6, jpeg.size)
            assertEquals(6, png.size)
            assertEquals("rotated-pages-p001.jpg", jpeg.first().name)
            assertEquals("rotated-pages-p006.png", png.last().name)
            val bitmap = BitmapFactory.decodeFile(png[1].path)
            try {
                assertEquals(1684.0, bitmap.width.toDouble(), 2.0)
                assertEquals(1190.0, bitmap.height.toDouble(), 2.0)
                assertEquals(Color.WHITE, bitmap.getPixel(0, 0))
            } finally {
                bitmap.recycle()
            }
        } finally {
            dir.deleteRecursively()
        }
    }
}
