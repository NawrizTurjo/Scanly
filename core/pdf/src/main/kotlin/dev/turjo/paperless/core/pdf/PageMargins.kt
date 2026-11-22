package dev.turjo.paperless.core.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.util.AtomicFile
import android.util.Log
import android.util.SizeF
import dev.turjo.paperless.core.model.PageRect
import dev.turjo.paperless.core.model.PdfKey
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

internal class PageMarginCache(private val directory: File) {
    @Synchronized
    fun getOrCompute(key: PdfKey, revision: String, index: Int, compute: () -> PageRect): PageRect {
        val file = file(key)
        val entries = read(file, revision)
        val page = index.toString()
        entries.optJSONArray(page)?.let {
            return PageRect(it.getDouble(0).toFloat(), it.getDouble(1).toFloat(),
                it.getDouble(2).toFloat(), it.getDouble(3).toFloat())
        }
        val box = compute()
        entries.put(page, JSONArray(listOf(box.left, box.top, box.right, box.bottom)))
        try {
            val json = JSONObject().put("revision", revision).put("boxes", entries).toString()
            val stream = file.startWrite()
            try {
                stream.write(json.toByteArray())
                file.finishWrite(stream)
            } catch (error: IOException) {
                file.failWrite(stream)
                throw error
            }
        } catch (error: IOException) {
            Log.w("PageMargins", "Could not cache margin boxes", error)
        }
        return box
    }

    @Synchronized
    fun invalidate(key: PdfKey) = file(key).delete()

    private fun read(file: AtomicFile, revision: String): JSONObject = try {
        val json = JSONObject(file.readFully().decodeToString())
        if (json.optString("revision") == revision) json.getJSONObject("boxes") else JSONObject()
    } catch (_: FileNotFoundException) {
        JSONObject()
    } catch (error: IOException) {
        Log.w("PageMargins", "Could not read margin boxes", error)
        JSONObject()
    } catch (_: JSONException) {
        JSONObject()
    }

    private fun file(key: PdfKey): AtomicFile {
        val hash = MessageDigest.getInstance("SHA-256").digest(key.value.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return AtomicFile(File(directory, "$hash.json"))
    }
}

internal fun detectContentBox(bitmap: Bitmap, size: SizeF): PageRect {
    val width = bitmap.width
    val height = bitmap.height
    val row = IntArray(width)
    var left = width
    var top = height
    var right = 0
    var bottom = 0
    for (y in 0 until height) {
        bitmap.getPixels(row, 0, width, 0, y, width, 1)
        for (x in row.indices) {
            val pixel = row[x]
            val luminance = 299 * Color.red(pixel) + 587 * Color.green(pixel) + 114 * Color.blue(pixel)
            if (luminance >= 235_000) continue
            left = minOf(left, x)
            top = minOf(top, y)
            right = maxOf(right, x + 1)
            bottom = maxOf(bottom, y + 1)
        }
    }
    if (left == width) return PageRect(0f, 0f, size.width, size.height)

    // A 3 px dilation kernel expands the extrema by 1 px. Split the 2% padding across both edges.
    val xPadding = 1 + (right - left) * 0.01f
    val yPadding = 1 + (bottom - top) * 0.01f
    val pointsPerPixel = size.width / width
    return PageRect(
        (left - xPadding).coerceAtLeast(0f) * pointsPerPixel,
        (top - yPadding).coerceAtLeast(0f) * pointsPerPixel,
        ((right + xPadding) * pointsPerPixel).coerceAtMost(size.width),
        ((bottom + yPadding) * pointsPerPixel).coerceAtMost(size.height),
    )
}
