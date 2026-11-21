package dev.turjo.paperless.core.imaging

import kotlin.math.ceil
import kotlin.math.floor

internal object Percentiles {
    fun histogram(samples: ByteArray): IntArray = IntArray(256).also { bins ->
        for (sample in samples) bins[sample.toInt() and 255]++
    }

    fun ofHistogram(bins: IntArray, percent: Double): Double {
        val count = bins.sum()
        return interpolate(count, percent) { index ->
            var end = 0
            bins.indexOfFirst {
                end += it
                index < end
            }.toDouble()
        }
    }

    fun ofFloats(samples: FloatArray, percent: Double): Double {
        val sorted = samples.sortedArray()
        return interpolate(sorted.size, percent) { sorted[it].toDouble() }
    }

    private inline fun interpolate(count: Int, percent: Double, at: (Int) -> Double): Double {
        require(count > 0 && percent in 0.0..100.0)
        val rank = percent / 100.0 * (count - 1)
        val floor = floor(rank)
        val low = at(floor.toInt())
        return low + (at(ceil(rank).toInt()) - low) * (rank - floor)
    }
}

