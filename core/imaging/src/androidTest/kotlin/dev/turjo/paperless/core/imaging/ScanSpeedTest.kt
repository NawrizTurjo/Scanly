package dev.turjo.paperless.core.imaging

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import kotlin.math.hypot
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfInt
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File
import java.nio.file.Files

/** Times a phone-sized (12 MP) page through detection and the default processing (logcat tag ScanSpeed). */
@RunWith(AndroidJUnit4::class)
class ScanSpeedTest {
    @Test
    fun twelveMegapixelPage() {
        OpenCv.ensureLoaded()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val dir = Files.createTempDirectory(instrumentation.targetContext.cacheDir.toPath(), "speed-").toFile()
        val input = File(dir, "input.jpg")
        val output = File(dir, "output.jpg")
        try {
            val bytes = instrumentation.context.assets.open("inputs/desk-sheet_05.jpg").use { it.readBytes() }
            MatOfByte(*bytes).useMat { encoded ->
                Imgcodecs.imdecode(encoded, Imgcodecs.IMREAD_COLOR).useMat { small ->
                    newMat { Imgproc.resize(small, it, Size(4080.0, 3072.0), 0.0, 0.0, Imgproc.INTER_CUBIC) }.useMat { big ->
                        MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, 95).useMat { Imgcodecs.imwrite(input.path, big, it) }
                    }
                }
            }
            repeat(2) { run ->
                var start = SystemClock.elapsedRealtime()
                val quad = detectOnFile(input).quad
                val detectMs = SystemClock.elapsedRealtime() - start
                assertNotNull(quad)
                // A live-overlay hint: the found quad, normalized, with each corner off by up to 1% of the frame.
                val offsets = floatArrayOf(0.008f, -0.006f, -0.01f, 0.004f, 0.007f, 0.009f, -0.005f, -0.008f)
                val hint = FloatArray(8) { (quad!![it] / if (it % 2 == 0) 4080f else 3072f) + offsets[it] }
                start = SystemClock.elapsedRealtime()
                val refined = refineOnFile(input, hint)
                val hintMs = SystemClock.elapsedRealtime() - start
                assertNotNull("hint should refine", refined)
                val worst = (0 until 4).maxOf { i -> hypot(refined!![2 * i] - quad!![2 * i], refined[2 * i + 1] - quad[2 * i + 1]) }
                Log.i("ScanSpeed", "run $run hint refine $hintMs ms, worst corner vs full detection ${"%.1f".format(worst)} px")
                assertTrue("refined hint should land on the detected page (worst $worst px)", worst < 0.01f * 4080f)
                for ((label, params) in listOf(
                    "document+deskew" to ProcessParams(quad, 0, "DOCUMENT", 0f, 0f, shadowFix = false, glareFix = false, deskew = true),
                    "document" to ProcessParams(quad, 0, "DOCUMENT", 0f, 0f, shadowFix = false, glareFix = false),
                    "auto+glare" to ProcessParams(quad, 0, "AUTO", 0f, 0f, shadowFix = false, glareFix = true),
                )) {
                    start = SystemClock.elapsedRealtime()
                    PageProcessor().process(input, params, output)
                    Log.i("ScanSpeed", "run $run detect $detectMs ms | $label ${SystemClock.elapsedRealtime() - start} ms")
                }
            }
        } finally {
            dir.deleteRecursively()
        }
    }
}
