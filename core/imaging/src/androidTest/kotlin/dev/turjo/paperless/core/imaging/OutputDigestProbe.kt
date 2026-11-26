package dev.turjo.paperless.core.imaging

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Logs a digest of every pipeline output (tag DIGEST), to compare two OpenCV builds byte for byte.
 * Runs only when asked: `am instrument -e digest 1 -e class ...OutputDigestProbe`. See
 * third_party/opencv/README.md.
 */
@RunWith(AndroidJUnit4::class)
class OutputDigestProbe {
    @Test fun logDigests() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("digest") == "1")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val assets = instrumentation.context.assets
        val dir = File(instrumentation.targetContext.cacheDir, "probe").apply { mkdirs() }
        val filters = listOf("DOCUMENT", "AUTO", "GRAY", "BW", "WHITEBOARD", "ORIGINAL")
        for (name in requireNotNull(assets.list("inputs")).sorted()) {
            val input = File(dir, name)
            assets.open("inputs/$name").use { source -> input.outputStream().use { source.copyTo(it) } }
            val detection = detectOnFile(input)
            val quad = detection.quad
            Log.i("DIGEST", "$name quad ${quad?.joinToString(",") { "%.3f".format(it) }}")
            for (filter in filters) {
                val output = File(dir, "out.jpg")
                PageProcessor().process(
                    input,
                    ProcessParams(quad, 0, filter, 0f, 0f, shadowFix = true, glareFix = filter == "AUTO", deskew = true),
                    output,
                )
                val digest = MessageDigest.getInstance("SHA-256").digest(output.readBytes())
                    .joinToString("") { "%02x".format(it) }.take(16)
                Log.i("DIGEST", "$name $filter $digest")
                output.delete()
            }
            input.delete()
        }
    }
}
