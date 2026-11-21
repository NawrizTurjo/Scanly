package dev.turjo.paperless.core.imaging

import android.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.core.Core
import org.opencv.imgcodecs.Imgcodecs
import java.io.File
import java.nio.file.Files

@RunWith(AndroidJUnit4::class)
class PageProcessorTest {
    @Test
    fun appliesExifThenUserRotationAndEncodesJpeg() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val dir = Files.createTempDirectory(instrumentation.targetContext.cacheDir.toPath(), "imaging-").toFile()
        val input = File(dir, "input.jpg")
        val output = File(dir, "output")
        try {
            instrumentation.context.assets.open("inputs/clean_01.jpg").use { source ->
                input.outputStream().use { source.copyTo(it) }
            }
            ExifInterface(input.path).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
                saveAttributes()
            }
            val params = ProcessParams(null, 180, "ORIGINAL", 0f, 0f, shadowFix = false, glareFix = false)
            val result = PageProcessor().process(input, params, output, jpegQuality = 100)

            Imgcodecs.imread(input.path, Imgcodecs.IMREAD_COLOR or Imgcodecs.IMREAD_IGNORE_ORIENTATION).useMat { raw ->
                assertEquals(raw.rows(), result.width)
                assertEquals(raw.cols(), result.height)
                assertFalse(result.glareWarning)
                Core.rotate(raw, raw, Core.ROTATE_90_COUNTERCLOCKWISE)
                Imgcodecs.imread(output.path).useMat { actual ->
                    assertEquals(raw.size(), actual.size())
                    newMat { Core.absdiff(raw, actual, it) }.useMat { difference ->
                        assertTrue(Core.mean(difference).`val`.take(3).average() < 3.0)
                    }
                }
            }
        } finally {
            output.delete()
            input.delete()
            dir.delete()
        }
    }
}

