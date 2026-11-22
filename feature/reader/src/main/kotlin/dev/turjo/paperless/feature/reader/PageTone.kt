package dev.turjo.paperless.feature.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix

internal enum class PageTone(val label: Int, val paperColor: Color) {
    NORMAL(R.string.reader_tone_normal, Color(0xFFF2F2F2)),
    NIGHT(R.string.reader_tone_night, Color(0xFF121212)),
    SEPIA(R.string.reader_tone_sepia, Color(0xFFF4ECD8)),
    WARM(R.string.reader_tone_warm, Color(0xFFFFF1C4)),
}

internal fun PageTone.readerBackground(normal: Color): Color = when (this) {
    PageTone.NORMAL -> normal
    PageTone.NIGHT -> Color(0xFF0C0C0C)
    PageTone.SEPIA -> Color(0xFFE4DCC8)
    PageTone.WARM -> Color(0xFFEFE1B4)
}

internal fun pageToneMatrix(tone: PageTone): FloatArray? = when (tone) {
    PageTone.NORMAL -> null
    PageTone.NIGHT -> {
        // Replace luminance with its inverse and retain 60% of the original chroma.
        val luminanceFactor = 214f / 255f + 0.60f
        val red = -0.2126f * luminanceFactor
        val green = -0.7152f * luminanceFactor
        val blue = -0.0722f * luminanceFactor
        floatArrayOf(
            red + 0.60f, green, blue, 0f, 232f,
            red, green + 0.60f, blue, 0f, 232f,
            red, green, blue + 0.60f, 0f, 232f,
            0f, 0f, 0f, 1f, 0f,
        )
    }
    PageTone.SEPIA -> {
        // Calibrate the classic sepia rows to cream paper after the 30% original mix.
        val red = (244f / 255f - 0.30f) / (0.393f + 0.769f + 0.189f)
        val green = (236f / 255f - 0.30f) / (0.349f + 0.686f + 0.168f)
        val blue = (216f / 255f - 0.30f) / (0.272f + 0.534f + 0.131f)
        floatArrayOf(
            0.30f + 0.393f * red, 0.769f * red, 0.189f * red, 0f, 0f,
            0.349f * green, 0.30f + 0.686f * green, 0.168f * green, 0f, 0f,
            0.272f * blue, 0.534f * blue, 0.30f + 0.131f * blue, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )
    }
    PageTone.WARM -> floatArrayOf(
        1f, 0f, 0f, 0f, 0f,
        0f, 241f / 255f, 0f, 0f, 0f,
        0f, 0f, 196f / 255f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    )
}

@Composable
internal fun rememberPageToneFilter(tone: PageTone): ColorFilter? = remember(tone) {
    pageToneMatrix(tone)?.let { ColorFilter.colorMatrix(ColorMatrix(it)) }
}
