package dev.turjo.paperless.core.pdf

import java.io.File

enum class PageSizeOption { AUTO, A4, LETTER }

enum class QualityPreset(val maxLongSide: Int?, val jpegQuality: Int?) {
    HIGH(null, null),
    MEDIUM(2200, 85),
    SMALL(1400, 70),
}

data class ExportPage(val jpeg: File, val widthPx: Int, val heightPx: Int)

data class SizeF(val width: Float, val height: Float)

data class PagePlacement(val x: Float, val y: Float, val w: Float, val h: Float)

object PageLayout {
    fun pageBox(option: PageSizeOption, imgW: Int, imgH: Int): SizeF {
        require(imgW > 0 && imgH > 0)
        val landscape = imgW > imgH
        return when (option) {
            PageSizeOption.AUTO -> if (landscape) {
                SizeF(842f, 842f * imgH / imgW)
            } else {
                SizeF(842f * imgW / imgH, 842f)
            }
            PageSizeOption.A4 -> if (landscape) SizeF(842f, 595f) else SizeF(595f, 842f)
            PageSizeOption.LETTER -> if (landscape) SizeF(792f, 612f) else SizeF(612f, 792f)
        }
    }

    fun placement(option: PageSizeOption, imgW: Int, imgH: Int): PagePlacement {
        val box = pageBox(option, imgW, imgH)
        if (option == PageSizeOption.AUTO) return PagePlacement(0f, 0f, box.width, box.height)

        val scale = minOf(box.width.toDouble() / imgW, box.height.toDouble() / imgH)
        val width = (imgW * scale).toFloat()
        val height = (imgH * scale).toFloat()
        return PagePlacement((box.width - width) / 2, (box.height - height) / 2, width, height)
    }
}
