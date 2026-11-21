package dev.turjo.paperless.core.imaging

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.ExifInterface
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class ScanModesTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun idCompositeCentersTrueSizeCardsOnA4() {
        val front = solidCard("front.jpg", Color.RED)
        val back = solidCard("back.jpg", Color.BLUE)
        val output = temporary.newFile("composite.jpg")
        IdCardComposer.compose(front, back, output)
        val exif = ExifInterface(output.path)
        assertEquals(300.0, exif.getAttributeDouble(ExifInterface.TAG_X_RESOLUTION, 0.0), 0.0)
        assertEquals(300.0, exif.getAttributeDouble(ExifInterface.TAG_Y_RESOLUTION, 0.0), 0.0)
        assertEquals(2, exif.getAttributeInt(ExifInterface.TAG_RESOLUTION_UNIT, 0))
        val page = requireNotNull(BitmapFactory.decodeFile(output.path))
        try {
            assertEquals(2480, page.width)
            assertEquals(3508, page.height)
            for ((centerY, color) in listOf(877 to Color.RED, 2631 to Color.BLUE)) {
                val horizontal = (0 until page.width).filter { matches(page.getPixel(it, centerY), color) }
                val vertical = (0 until page.height).filter { matches(page.getPixel(1240, it), color) }
                assertEquals(734, horizontal.first())
                assertEquals(1011, horizontal.size)
                assertEquals(centerY - 319, vertical.first())
                assertEquals(638, vertical.size)
            }
            assertEquals(Color.WHITE, page.getPixel(1240, 1754))
        } finally {
            page.recycle()
        }
    }

    @Test fun bookSplitMapsBothInnerEdgesToTheFixtureGutter() {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val truth = assets.open("truth.json").bufferedReader().use { JSONObject(it.readText()).getJSONObject("images") }
        for (filename in requireNotNull(assets.list("books")).sorted()) {
            val path = "books/$filename"
            val fixture = truth.getJSONObject(path)
            val corners = fixture.getJSONArray("corners")
            val spread = FloatArray(8) { corners.getJSONArray(it / 2).getDouble(it % 2).toFloat() }
            val gutter = fixture.getJSONArray("gutter")
            val width = fixture.getJSONArray("size").getInt(0)
            val input = temporary.newFile(filename)
            assets.open(path).use { source -> input.outputStream().use { source.copyTo(it) } }
            val (left, right) = requireNotNull(BookSplitter.split(input, spread)) { "No split for $path" }
            for ((index, edges) in listOf((2 to 0), (4 to 6)).withIndex()) {
                val x = gutter.getJSONArray(index).getDouble(0)
                val y = gutter.getJSONArray(index).getDouble(1)
                val dx = abs(left[edges.first] - x)
                val dy = abs(left[edges.first + 1] - y)
                assertEquals(left[edges.first], right[edges.second], 0f)
                assertEquals(left[edges.first + 1], right[edges.second + 1], 0f)
                assertTrue("$path gutter point $index differs by ($dx, $dy) px", maxOf(dx, dy) <= width * 0.02)
                Log.i("ScanModesTest", "$path point $index error ($dx, $dy) px / width $width")
            }
        }
    }

    @Test fun bookSplitRejectsAUniformSpread() {
        OpenCv.ensureLoaded()
        Mat(200, 300, CvType.CV_8UC3, Scalar(255.0, 255.0, 255.0)).useMat { image ->
            assertEquals(null, BookSplitter.split(image, floatArrayOf(0f, 0f, 299f, 0f, 299f, 199f, 0f, 199f)))
        }
    }

    private fun solidCard(name: String, color: Int) = temporary.newFile(name).also { file ->
        val bitmap = Bitmap.createBitmap(160, 100, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(color)
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
    }

    private fun matches(pixel: Int, color: Int): Boolean = if (color == Color.RED) {
        Color.green(pixel) < 128 && Color.red(pixel) > Color.blue(pixel)
    } else {
        Color.green(pixel) < 128 && Color.blue(pixel) > Color.red(pixel)
    }
}
