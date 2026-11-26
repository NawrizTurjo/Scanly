package dev.turjo.paperless.core.ocr

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OcrDigestProbe {
    @Test
    fun logDigests() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("digest") == "1")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val recognizer = TesseractRecognizer(context)
        val input = File.createTempFile("ocr-probe", ".jpg", context.cacheDir)
        try {
            for (language in listOf("eng", "ben", "eng+ben")) {
                for (name in listOf("clean_01.jpg", "clean_03.jpg", "clean_05.jpg")) {
                    instrumentation.context.assets.open(name).use { source ->
                        input.outputStream().use { source.copyTo(it) }
                    }
                    val text = recognizer.recognize(input, language).text
                    val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
                        .joinToString("") { "%02x".format(it) }
                    val preview = text.take(80).replace("\r", "\\r").replace("\n", "\\n")
                    Log.i("OCRDIGEST", "$name $language $digest $preview")
                }
            }
        } finally {
            input.delete()
            recognizer.release()
        }
    }
}
