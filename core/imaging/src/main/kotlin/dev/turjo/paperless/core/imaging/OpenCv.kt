package dev.turjo.paperless.core.imaging

import org.opencv.android.OpenCVLoader
import org.opencv.core.Mat

object OpenCv {
    private val loaded by lazy { check(OpenCVLoader.initLocal()) { "OpenCV failed to load" } }

    fun ensureLoaded() {
        loaded
    }
}

internal inline fun <T : Mat, R> T.useMat(block: (T) -> R): R =
    try {
        block(this)
    } finally {
        release()
    }

internal inline fun newMat(fill: (Mat) -> Unit): Mat {
    val mat = Mat()
    try {
        fill(mat)
        return mat
    } catch (failure: Throwable) {
        mat.release()
        throw failure
    }
}

