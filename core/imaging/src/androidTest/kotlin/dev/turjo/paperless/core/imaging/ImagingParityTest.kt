package dev.turjo.paperless.core.imaging

import android.content.res.AssetManager
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.nio.ByteBuffer
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot

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
        val times = mutableListOf<Double>()
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
            val truth = item.getJSONArray("quad").toQuad()
            val dimensions = item.getJSONArray("warp_size")
            val size = Size(dimensions.getDouble(0), dimensions.getDouble(1))
            assets.image("warped/$name.png").useMat { goldenWarp ->
                assets.image(item.getString("input")).useMat { input ->
                    val start = SystemClock.elapsedRealtimeNanos()
                    val found = detector.detect(input)
                    val elapsedMs = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
                    times.add(elapsedMs)
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
                    item.optJSONObject("aspect_prior")?.let { prior ->
                        val expected = prior.getJSONArray("quad").toQuad()
                        val startPrior = SystemClock.elapsedRealtimeNanos()
                        val actual = detector.detect(input, aspectPrior = 1.586f)
                        val elapsed = (SystemClock.elapsedRealtimeNanos() - startPrior) / 1_000_000.0
                        val error = actual.quad?.let { Geometry.cornerError(it, expected).second }
                        Log.i(TAG, "$name/aspect prior | ${elapsed.formatted()} ms | ${error?.formatted()} px")
                        if (error == null || error > quadTolerance) failures.add("$name/aspect prior: $error px")
                    }
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
        logTimings("full detection", times)
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun liveDetectorMatchesPythonGoldens() {
        OpenCv.ensureLoaded()
        val metadata = assets.open("goldens.json").bufferedReader().use { JSONObject(it.readText()) }
        val tolerance = metadata.getJSONObject("tolerances").getDouble("live_quad_px")
        val items = metadata.getJSONObject("items")
        val times = mutableListOf<Double>()
        val failures = mutableListOf<String>()
        val detector = LiveQuadDetector()
        try {
            for (name in items.keys()) {
                val live = items.getJSONObject(name).getJSONObject("live")
                val expected = live.optJSONArray("quad")?.toQuad()
                assets.image(live.getString("frame"), Imgcodecs.IMREAD_GRAYSCALE).useMat { gray ->
                    fun compare(variant: String, input: LumaFrame): LiveQuad? {
                        val start = SystemClock.elapsedRealtimeNanos()
                        val actual = detector.detect(input)
                        val elapsed = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
                        if (variant == "upright") times.add(elapsed)
                        val error = if (expected == null) null else actual?.let {
                            (0 until 8 step 2).maxOf { i ->
                                hypot(
                                    it.points[i] * gray.cols() - expected[i],
                                    it.points[i + 1] * gray.rows() - expected[i + 1],
                                ).toDouble()
                            }
                        }
                        Log.i(TAG, "live $name/$variant | ${elapsed.formatted()} ms | ${error?.formatted()} px")
                        val matches = if (expected == null) actual == null else error != null && error <= tolerance
                        if (!matches) failures.add("$name/$variant: $error px, expected quad=${expected != null}, detected=${actual != null}")
                        return actual
                    }

                    val upright = compare("upright", gray.lumaFrame())
                    newMat { Core.rotate(gray, it, Core.ROTATE_90_COUNTERCLOCKWISE) }.useMat { sensor ->
                        val rotated = compare("rotated", sensor.lumaFrame(rotation = 90))
                        if (!sameDetection(upright, rotated)) failures.add("$name rotated detection differed from upright")
                    }
                    if (times.size == 1) {
                        val padded = gray.lumaFrame(padding = 13, offset = 7)
                        val found = compare("padded", padded)
                        if (!sameDetection(upright, found)) failures.add("$name padded detection differed from upright")
                        if (padded.data.position() != 7) failures.add("$name padded buffer position changed")
                    }
                }
            }
            Mat.zeros(360, 480, CvType.CV_8UC1).useMat { blank ->
                assertNull("A blank frame should have no quad", detector.detect(blank.lumaFrame()))
            }
        } finally {
            detector.release()
        }
        logTimings("live upright", times)
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun liveSharpnessAndFingerprint() {
        val detector = LiveQuadDetector()
        try {
            val metadata = assets.open("goldens.json").bufferedReader().use { JSONObject(it.readText()) }
            val items = metadata.getJSONObject("items")
            fun frame(name: String): Mat = assets.image(
                items.getJSONObject(name).getJSONObject("live").getString("frame"),
                Imgcodecs.IMREAD_GRAYSCALE,
            )

            OpenCv.ensureLoaded()
            frame("clean_01").useMat { original ->
                val sharp = requireNotNull(detector.detect(original.lumaFrame()))
                val repeat = requireNotNull(detector.detect(original.lumaFrame()))
                val blurred = newMat { Imgproc.GaussianBlur(original, it, Size(), 2.0) }.useMat {
                    requireNotNull(detector.detect(it.lumaFrame()))
                }
                val other = frame("tables_05").useMat { requireNotNull(detector.detect(it.lumaFrame())) }
                assertTrue(sharp.fingerprint.size == 256 && sharp.fingerprint.all { it in 0f..1f })
                assertTrue(sharp.fingerprint.contentEquals(repeat.fingerprint))
                val distance = sharp.fingerprint.indices.map { abs(sharp.fingerprint[it] - other.fingerprint[it]) }.average()
                Log.i(TAG, "live sharpness ${sharp.sharpness} -> ${blurred.sharpness} | fingerprint distance $distance")
                assertTrue("Sharpness should drop after blur", blurred.sharpness < sharp.sharpness)
                assertTrue("Fingerprint mean absolute difference: $distance", distance > 0.05)
            }
        } finally {
            detector.release()
        }
    }

    @Test
    fun pageToolsMatchPythonGoldens() {
        OpenCv.ensureLoaded()
        val dir = "pagetools"
        val metadata = assets.open("$dir/pagetools.json").bufferedReader().use { JSONObject(it.readText()) }
        val tolerances = metadata.getJSONObject("tolerances")
        val items = metadata.getJSONObject("items")
        val failures = mutableListOf<String>()
        for (name in items.keys()) {
            val item = items.getJSONObject(name)
            val (error, limit) = assets.image("$dir/${item.optString("input", name)}").useMat { input ->
                when (item.getString("tool")) {
                    "skew" -> {
                        val start = SystemClock.elapsedRealtimeNanos()
                        val (output, angle) = PageTools.deskew(input)
                        val elapsedMs = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
                        output.release()
                        Log.i(TAG, "$name | angle ${angle.formatted()} | deskew ${elapsedMs.formatted()} ms")
                        abs(angle - item.getDouble("angle")) to tolerances.getDouble("angle_deg")
                    }
                    "ink_fraction" -> {
                        val fraction = PageTools.inkFraction(input)
                        Log.i(TAG, "$name | ink fraction ${String.format(Locale.US, "%.6f", fraction)}")
                        abs(fraction - item.getDouble("value")) to tolerances.getDouble("ink_fraction_abs")
                    }
                    "remove_ink" -> {
                        val colour = InkColour.valueOf(item.getString("colour").uppercase(Locale.ROOT))
                        val mean = PageTools.removeInk(input, colour).useMat { result ->
                            assets.image("$dir/$name").useMat { diff(result, it).mean }
                        }
                        Log.i(TAG, "$name | mean absolute difference ${mean.formatted()}")
                        mean to tolerances.getDouble("pixel_mean_abs")
                    }
                    "gutter" -> {
                        val (x, confidence) = PageTools.findGutter(input)
                        Log.i(TAG, "$name | gutter x $x | confidence ${confidence.formatted()}")
                        abs(x.toDouble() - item.getInt("x")) to tolerances.getDouble("gutter_px")
                    }
                    else -> error("Unknown page tool for $name")
                }
            }
            if (error > limit) failures.add("$name: error $error exceeds $limit")
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    private fun Mat.lumaFrame(rotation: Int = 0, padding: Int = 0, offset: Int = 0): LumaFrame {
        val width = cols()
        val height = rows()
        val stride = width + padding
        val bytes = ByteBuffer.allocateDirect(offset + (height - 1) * stride + width)
        val row = ByteArray(width)
        for (y in 0 until height) {
            get(y, 0, row)
            bytes.position(offset + y * stride)
            bytes.put(row)
        }
        bytes.position(offset)
        return LumaFrame(bytes, width, height, stride, rotation)
    }

    private fun AssetManager.image(path: String, mode: Int = Imgcodecs.IMREAD_COLOR): Mat {
        val bytes = open(path).use { it.readBytes() }
        return MatOfByte(*bytes).useMat {
            Imgcodecs.imdecode(it, mode).also { decoded ->
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

    private fun sameDetection(a: LiveQuad?, b: LiveQuad?): Boolean =
        if (a == null || b == null) a == b else a.points.contentEquals(b.points) && a.confidence == b.confidence

    private fun JSONArray.toQuad() = FloatArray(8) {
        getJSONArray(it / 2).getDouble(it % 2).toFloat()
    }

    private fun logTimings(label: String, times: List<Double>) {
        val sorted = times.sorted()
        val median = (sorted[(sorted.size - 1) / 2] + sorted[sorted.size / 2]) / 2
        val p95 = sorted[ceil(0.95 * sorted.size).toInt() - 1]
        Log.i(TAG, "$label timings (n=${sorted.size}) | median ${median.formatted()} ms | p95 ${p95.formatted()} ms")
    }

    private fun Double.formatted(): String = String.format(Locale.US, "%.4f", this)

    companion object {
        private const val TAG = "ImagingParity"
    }
}
