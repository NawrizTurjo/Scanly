package dev.turjo.paperless.core.pdf.tools

import android.graphics.Color
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceGray
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.graphics.pattern.PDTilingPattern
import java.io.File
import java.io.DataOutputStream
import java.io.OutputStream
import java.util.Collections
import java.util.IdentityHashMap
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt

object PdfGrayscale {
    suspend fun convert(input: File, out: File): ToolResult = editPdf(input, out) { document, temporary ->
        val visited = Collections.newSetFromMap(IdentityHashMap<COSDictionary, Boolean>())
        val converted = Collections.newSetFromMap(IdentityHashMap<COSStream, Boolean>())
        for (page in document.pages) {
            currentCoroutineContext().ensureActive()
            convertResources(document, page.resources, visited, converted)
            for (annotation in page.annotations) {
                val appearance = annotation.appearance ?: continue
                for (entry in listOf(appearance.normalAppearance, appearance.rolloverAppearance, appearance.downAppearance)) {
                    if (entry == null) continue
                    val streams = if (entry.isStream) listOf(entry.appearanceStream) else entry.subDictionary.values
                    for (stream in streams) convertResources(document, stream.resources, visited, converted)
                }
            }
        }
        document.save(temporary)
        ToolResult(out, document.numberOfPages, "Text and vector colours unchanged")
    }

    private suspend fun convertResources(
        document: PDDocument,
        resources: PDResources?,
        visited: MutableSet<COSDictionary>,
        converted: MutableSet<COSStream>,
    ) {
        if (resources == null || !visited.add(resources.cosObject)) return
        for (name in resources.xObjectNames.toList()) {
            currentCoroutineContext().ensureActive()
            when (val item = resources.getXObject(name)) {
                is PDFormXObject -> convertResources(document, item.resources, visited, converted)
                is PDImageXObject -> if (!item.isStencil && converted.add(item.cosObject) && item.colorSpace != PDDeviceGray.INSTANCE) {
                    grayImage(document, item)
                }
            }
        }
        for (name in resources.patternNames) {
            val pattern = resources.getPattern(name) as? PDTilingPattern ?: continue
            convertResources(document, pattern.resources, visited, converted)
        }
    }

    private suspend fun grayImage(document: PDDocument, image: PDImageXObject) {
        val original = image.image ?: throw ToolError.Corrupt("Cannot decode image")
        val width = original.width
        val height = original.height
        if (width > 65535 || height > 65535) throw ToolError.Invalid("Image is too large for JPEG")
        val samples = ByteArray(width * height)
        val pixels = IntArray(width)
        val alpha = if (original.hasAlpha()) ByteArray(samples.size) else null
        var transparent = false
        for (y in 0 until height) {
            currentCoroutineContext().ensureActive()
            original.getPixels(pixels, 0, width, 0, y, width, 1)
            pixels.forEachIndexed { x, pixel ->
                val index = y * width + x
                val luminance = (299 * Color.red(pixel) + 587 * Color.green(pixel) + 114 * Color.blue(pixel) + 500) / 1000
                samples[index] = luminance.toByte()
                if (alpha != null) {
                    val opacity = Color.alpha(pixel)
                    alpha[index] = opacity.toByte()
                    transparent = transparent || opacity != 255
                }
            }
        }

        val target = image.cosObject
        for (key in listOf(COSName.DECODE, COSName.DECODE_PARMS, COSName.MASK, COSName.SMASK, COSName.CS)) {
            target.removeItem(key)
        }
        target.removeItem(COSName.getPDFName("SMaskInData"))
        target.setInt(COSName.BITS_PER_COMPONENT, 8)
        target.setItem(COSName.COLORSPACE, COSName.DEVICEGRAY)
        target.setItem(COSName.FILTER, COSName.DCT_DECODE)
        target.createRawOutputStream().use { GrayJpeg.encode(samples, width, height, it) }
        if (transparent && alpha != null) {
            val mask = document.document.createCOSStream().apply {
                setItem(COSName.TYPE, COSName.XOBJECT)
                setItem(COSName.SUBTYPE, COSName.IMAGE)
                setItem(COSName.COLORSPACE, COSName.DEVICEGRAY)
                setInt(COSName.WIDTH, width)
                setInt(COSName.HEIGHT, height)
                setInt(COSName.BITS_PER_COMPONENT, 8)
                createOutputStream(COSName.FLATE_DECODE).use { it.write(alpha) }
            }
            target.setItem(COSName.SMASK, mask)
        }
    }
}

// Bitmap.compress does not accept ALPHA_8, so write a baseline JPEG with one luminance component.
private object GrayJpeg {
    private val zigzag = intArrayOf(
        0, 1, 8, 16, 9, 2, 3, 10, 17, 24, 32, 25, 18, 11, 4, 5,
        12, 19, 26, 33, 40, 48, 41, 34, 27, 20, 13, 6, 7, 14, 21, 28,
        35, 42, 49, 56, 57, 50, 43, 36, 29, 22, 15, 23, 30, 37, 44, 51,
        58, 59, 52, 45, 38, 31, 39, 46, 53, 60, 61, 54, 47, 55, 62, 63,
    )
    // JPEG Annex K luminance quantization table, scaled to quality 85.
    private val quantization = intArrayOf(
        16, 11, 10, 16, 24, 40, 51, 61,
        12, 12, 14, 19, 26, 58, 60, 55,
        14, 13, 16, 24, 40, 57, 69, 56,
        14, 17, 22, 29, 51, 87, 80, 62,
        18, 22, 37, 56, 68, 109, 103, 77,
        24, 35, 55, 64, 81, 104, 113, 92,
        49, 64, 78, 87, 103, 121, 120, 101,
        72, 92, 95, 98, 112, 100, 103, 99,
    ).map { (it * 30 + 50) / 100 }
    private val basis = Array(8) { frequency ->
        DoubleArray(8) { sample ->
            0.5 * cos((2 * sample + 1) * frequency * PI / 16) *
                if (frequency == 0) 0.7071067811865476 else 1.0
        }
    }
    private val acSymbols = listOf(0, 0xf0) + (1..160).map { index ->
        val run = (index - 1) / 10
        val size = (index - 1) % 10 + 1
        run * 16 + size
    }
    private val acCodes = IntArray(256).apply { acSymbols.forEachIndexed { code, symbol -> this[symbol] = code } }

    suspend fun encode(samples: ByteArray, width: Int, height: Int, output: OutputStream) {
        val stream = DataOutputStream(output)
        stream.writeShort(0xffd8)
        stream.segment(0xdb, 67, 0, *zigzag.map { quantization[it] }.toIntArray())
        stream.segment(0xc0, 11, 8, height shr 8, height, width shr 8, width, 1, 1, 0x11, 0)
        stream.huffmanTable(0, 4, (0..11).toList())
        stream.huffmanTable(0x10, 8, acSymbols)
        stream.segment(0xda, 8, 1, 1, 0, 0, 63, 0)

        val bits = EntropyWriter(stream)
        val rows = DoubleArray(64)
        val coefficients = IntArray(64)
        var previousDc = 0
        for (top in 0 until height step 8) {
            currentCoroutineContext().ensureActive()
            for (left in 0 until width step 8) {
                for (y in 0..7) {
                    val offset = minOf(top + y, height - 1) * width
                    for (u in 0..7) {
                        var total = 0.0
                        for (x in 0..7) {
                            val sample = samples[offset + minOf(left + x, width - 1)].toInt() and 255
                            total += (sample - 128) * basis[u][x]
                        }
                        rows[y * 8 + u] = total
                    }
                }
                for (v in 0..7) for (u in 0..7) {
                    var total = 0.0
                    for (y in 0..7) total += rows[y * 8 + u] * basis[v][y]
                    val index = v * 8 + u
                    coefficients[index] = (total / quantization[index]).roundToInt()
                }

                val dc = coefficients[0]
                val difference = dc - previousDc
                val dcSize = magnitude(difference)
                bits.write(dcSize, 4)
                bits.amplitude(difference, dcSize)
                previousDc = dc
                var zeros = 0
                for (index in 1..63) {
                    val coefficient = coefficients[zigzag[index]]
                    if (coefficient == 0) {
                        zeros++
                        continue
                    }
                    while (zeros >= 16) {
                        bits.write(acCodes[0xf0], 8)
                        zeros -= 16
                    }
                    val size = magnitude(coefficient)
                    bits.write(acCodes[zeros * 16 + size], 8)
                    bits.amplitude(coefficient, size)
                    zeros = 0
                }
                if (zeros > 0) bits.write(acCodes[0], 8)
            }
        }
        bits.finish()
        stream.writeShort(0xffd9)
    }

    private fun magnitude(value: Int): Int = Int.SIZE_BITS - kotlin.math.abs(value).countLeadingZeroBits()

    private fun DataOutputStream.segment(marker: Int, size: Int, vararg bytes: Int) {
        writeShort(0xff00 or marker)
        writeShort(size)
        for (byte in bytes) writeByte(byte)
    }

    private fun DataOutputStream.huffmanTable(id: Int, length: Int, symbols: List<Int>) {
        segment(0xc4, 19 + symbols.size, id)
        for (size in 1..16) writeByte(if (size == length) symbols.size else 0)
        for (symbol in symbols) writeByte(symbol)
    }

    private class EntropyWriter(private val output: OutputStream) {
        private var buffer = 0
        private var pending = 0

        fun write(value: Int, size: Int) {
            buffer = (buffer shl size) or value
            pending += size
            while (pending >= 8) {
                pending -= 8
                val byte = (buffer shr pending) and 255
                output.write(byte)
                if (byte == 255) output.write(0)
            }
            buffer = buffer and ((1 shl pending) - 1)
        }

        fun amplitude(value: Int, size: Int) {
            if (value != 0) write(if (value > 0) value else value + (1 shl size) - 1, size)
        }

        fun finish() {
            val padding = (8 - pending) % 8
            write((1 shl padding) - 1, padding)
        }
    }
}
