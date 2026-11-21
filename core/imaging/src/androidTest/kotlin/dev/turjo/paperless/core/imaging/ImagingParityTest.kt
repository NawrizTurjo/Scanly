package dev.turjo.paperless.core.imaging

import android.content.res.AssetManager
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class ImagingParityTest {
    private val assets = InstrumentationRegistry.getInstrumentation().context.assets

    @Test
    fun matchesPythonGoldens() {
        OpenCv.ensureLoaded()
        val metadata = assets.open("goldens.json").bufferedReader().use { JSONObject(it.readText()) }
        val tolerances = metadata.getJSONObject("tolerances")
        val quadTolerance = tolerances.getDouble("quad_px")
        val meanTolerance = tolerances.getDouble("filter_mean_abs")
        val shareTolerance = tolerances.getDouble("filter_max_share_over_32")
        val items = metadata.getJSONObject("items")
        val files = requireNotNull(assets.list("filters")).toSet()
        val failures = mutableListOf<String>()
        val detector = QuadDetector()
        val filters = mapOf<String, (Mat) -> Mat>(
            "AUTO" to Enhancer::auto,
            "DOCUMENT" to Enhancer::document,
            "BW" to Enhancer::bw,
            "GRAY" to Enhancer::gray,
            "WHITEBOARD" to Enhancer::whiteboard,
        )

        Log.i(TAG, "item | detection ms | max corner px | warp mean abs | glare warning")
        for (name in items.keys()) {
            val item = items.getJSONObject(name)
            val corners = item.getJSONArray("quad")
            val truth = FloatArray(8) { corners.getJSONArray(it / 2).getDouble(it % 2).toFloat() }
            val dimensions = item.getJSONArray("warp_size")
            val size = Size(dimensions.getDouble(0), dimensions.getDouble(1))
            assets.image("warped/$name.png").useMat { goldenWarp ->
                assets.image(item.getString("input")).useMat { input ->
                    val start = SystemClock.elapsedRealtimeNanos()
                    val found = detector.detect(input)
                    val elapsedMs = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
                    val maxCorner = found.quad?.let { Geometry.cornerError(it, truth).second }
                    val warpMean = Geometry.warp(input, truth, size).useMat { warp ->
                        val mean = diff(warp, goldenWarp).mean
                        val (fixed, warning) = Enhancer.glareFix(warp)
                        fixed.release()
                        Log.i(TAG, "$name | ${elapsedMs.formatted()} | ${maxCorner?.formatted()} | ${mean.formatted()} | $warning")
                        if (warning != item.getBoolean("glare_warning")) failures.add("$name glare warning: $warning")
                        mean
                    }
                    if (maxCorner == null || maxCorner > quadTolerance) failures.add("$name max corner: $maxCorner px")
                    if (warpMean > 3.0) failures.add("$name warp mean: $warpMean")
                }
                for ((filter, apply) in filters) {
                    val file = "${name}_$filter.png"
                    if (file !in files) continue
                    val start = SystemClock.elapsedRealtimeNanos()
                    apply(goldenWarp).useMat { actual ->
                        val elapsedMs = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
                        val diff = assets.image("filters/$file").useMat { diff(actual, it) }
                        Log.i(TAG, "$name/$filter | ${elapsedMs.formatted()} ms | mean ${diff.mean.formatted()} | >32 share ${diff.share.formatted()}")
                        if (diff.mean > meanTolerance || diff.share > shareTolerance) failures.add("$file: $diff")
                    }
                }
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    private fun AssetManager.image(path: String): Mat {
        val bytes = open(path).use { it.readBytes() }
        return MatOfByte(*bytes).useMat {
            Imgcodecs.imdecode(it, Imgcodecs.IMREAD_COLOR).also { decoded ->
                if (decoded.empty()) {
                    decoded.release()
                    error("Could not decode $path")
                }
            }
        }
    }

    private data class Difference(val mean: Double, val share: Double)

    private fun diff(actual: Mat, expected: Mat): Difference {
        return newMat { Core.absdiff(actual, expected, it) }.useMat { delta ->
            val row = ByteArray(delta.cols() * 3)
            var pixelsOver = 0
            for (y in 0 until delta.rows()) {
                delta.get(y, 0, row)
                for (i in 0 until row.size step 3) {
                    if (maxOf(row[i].unsigned(), row[i + 1].unsigned(), row[i + 2].unsigned()) > 32) pixelsOver++
                }
            }
            Difference(Core.mean(delta).`val`.take(3).average(), pixelsOver.toDouble() / delta.total())
        }
    }

    private fun Double.formatted(): String = String.format(Locale.US, "%.4f", this)

    companion object {
        private const val TAG = "ImagingParity"
    }
}

