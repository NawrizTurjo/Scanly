package dev.turjo.paperless.core.designsystem

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin

/** A seed in perceptual terms: CIE LCh hue in degrees and chroma (vividness). */
data class SeedColor(val hue: Float, val chroma: Float) {
    /** The seed itself, shown at Material's primary tone. */
    fun toColor(): Color = lchColor(40f, chroma, hue)

    companion object {
        fun of(color: Color): SeedColor {
            val (_, c, h) = colorToLch(color)
            return SeedColor(h, c)
        }
    }
}

/**
 * A full Material 3 colour scheme from one seed colour, so a custom accent behaves like the
 * built-in ones. Tonal palettes are built in CIE LCh (L* is the lightness Material's tone numbers
 * refer to) and mapped to roles with Material's tone table. Out-of-gamut colours lose chroma, not
 * lightness, so every tone keeps its contrast.
 */
fun seedColorScheme(seed: SeedColor, dark: Boolean): ColorScheme {
    val vivid = seed.chroma.coerceIn(16f, 70f)
    val primary = Palette(seed.hue, vivid)
    val secondary = Palette(seed.hue, (vivid / 3f).coerceIn(8f, 18f))
    val tertiary = Palette((seed.hue + 60f) % 360f, (vivid * 0.6f).coerceIn(16f, 36f))
    val neutral = Palette(seed.hue, 4f)
    val variant = Palette(seed.hue, 8f)
    val error = Palette(25f, 60f)
    return if (!dark) lightColorScheme(
        primary = primary(40), onPrimary = primary(100), primaryContainer = primary(90), onPrimaryContainer = primary(10),
        inversePrimary = primary(80),
        secondary = secondary(40), onSecondary = secondary(100), secondaryContainer = secondary(90),
        onSecondaryContainer = secondary(10),
        tertiary = tertiary(40), onTertiary = tertiary(100), tertiaryContainer = tertiary(90),
        onTertiaryContainer = tertiary(10),
        background = neutral(98), onBackground = neutral(10),
        surface = neutral(98), onSurface = neutral(10), surfaceVariant = variant(90), onSurfaceVariant = variant(30),
        surfaceTint = primary(40), inverseSurface = neutral(20), inverseOnSurface = neutral(95),
        error = error(40), onError = error(100), errorContainer = error(90), onErrorContainer = error(10),
        outline = variant(50), outlineVariant = variant(80), scrim = Color.Black,
        surfaceBright = neutral(98), surfaceDim = neutral(87), surfaceContainerLowest = neutral(100),
        surfaceContainerLow = neutral(96), surfaceContainer = neutral(94), surfaceContainerHigh = neutral(92),
        surfaceContainerHighest = neutral(90),
    ) else darkColorScheme(
        primary = primary(80), onPrimary = primary(20), primaryContainer = primary(30), onPrimaryContainer = primary(90),
        inversePrimary = primary(40),
        secondary = secondary(80), onSecondary = secondary(20), secondaryContainer = secondary(30),
        onSecondaryContainer = secondary(90),
        tertiary = tertiary(80), onTertiary = tertiary(20), tertiaryContainer = tertiary(30),
        onTertiaryContainer = tertiary(90),
        background = neutral(6), onBackground = neutral(90),
        surface = neutral(6), onSurface = neutral(90), surfaceVariant = variant(30), onSurfaceVariant = variant(80),
        surfaceTint = primary(80), inverseSurface = neutral(90), inverseOnSurface = neutral(20),
        error = error(80), onError = error(20), errorContainer = error(30), onErrorContainer = error(90),
        outline = variant(60), outlineVariant = variant(30), scrim = Color.Black,
        surfaceBright = neutral(24), surfaceDim = neutral(6), surfaceContainerLowest = neutral(4),
        surfaceContainerLow = neutral(10), surfaceContainer = neutral(12), surfaceContainerHigh = neutral(17),
        surfaceContainerHighest = neutral(22),
    )
}

private class Palette(private val hue: Float, private val chroma: Float) {
    operator fun invoke(tone: Int): Color = lchColor(tone.toFloat(), chroma, hue)
}

// D65 reference white.
private const val XN = 0.95047
private const val YN = 1.0
private const val ZN = 1.08883

/** CIE LCh (L* 0..100, chroma, hue degrees) to sRGB, lowering chroma until it fits the gamut. */
internal fun lchColor(lightness: Float, chroma: Float, hue: Float): Color {
    if (lightness <= 0f) return Color.Black
    if (lightness >= 100f) return Color.White
    var low = 0.0
    var high = chroma.toDouble()
    var best = labToSrgb(lightness.toDouble(), 0.0, hue.toDouble())!!
    if (labToSrgb(lightness.toDouble(), high, hue.toDouble())?.also { best = it } != null) return best.toColor()
    repeat(16) {
        val mid = (low + high) / 2
        val rgb = labToSrgb(lightness.toDouble(), mid, hue.toDouble())
        if (rgb != null) {
            best = rgb
            low = mid
        } else {
            high = mid
        }
    }
    return best.toColor()
}

private fun DoubleArray.toColor() = Color(this[0].toFloat(), this[1].toFloat(), this[2].toFloat())

/** Lab (from L, C, h) to gamma-encoded sRGB, or null when outside the sRGB gamut. */
private fun labToSrgb(l: Double, c: Double, hueDegrees: Double): DoubleArray? {
    val h = Math.toRadians(hueDegrees)
    val a = c * cos(h)
    val b = c * sin(h)
    val fy = (l + 16) / 116
    val fx = fy + a / 500
    val fz = fy - b / 200
    fun inverse(t: Double) = if (t.pow(3) > 0.008856) t.pow(3) else (t - 16.0 / 116) / 7.787
    val x = XN * inverse(fx)
    val y = YN * inverse(fy)
    val z = ZN * inverse(fz)
    val linear = doubleArrayOf(
        3.2404542 * x - 1.5371385 * y - 0.4985314 * z,
        -0.9692660 * x + 1.8760108 * y + 0.0415560 * z,
        0.0556434 * x - 0.2040259 * y + 1.0572252 * z,
    )
    if (linear.any { it < -1e-4 || it > 1 + 1e-4 }) return null
    return DoubleArray(3) { index ->
        val v = linear[index].coerceIn(0.0, 1.0)
        if (v <= 0.0031308) 12.92 * v else 1.055 * v.pow(1 / 2.4) - 0.055
    }
}

/** sRGB to CIE LCh: (L*, chroma, hue degrees). */
internal fun colorToLch(color: Color): Triple<Float, Float, Float> {
    fun linear(v: Float) = if (v <= 0.04045f) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    val r = linear(color.red)
    val g = linear(color.green)
    val b = linear(color.blue)
    val x = (0.4124564 * r + 0.3575761 * g + 0.1804375 * b) / XN
    val y = (0.2126729 * r + 0.7151522 * g + 0.0721750 * b) / YN
    val z = (0.0193339 * r + 0.1191920 * g + 0.9503041 * b) / ZN
    fun f(t: Double) = if (t > 0.008856) cbrt(t) else 7.787 * t + 16.0 / 116
    val l = 116 * f(y) - 16
    val a = 500 * (f(x) - f(y))
    val bb = 200 * (f(y) - f(z))
    val hue = (Math.toDegrees(atan2(bb, a)) + 360) % 360
    return Triple(l.toFloat(), hypot(a, bb).toFloat(), hue.toFloat())
}

/** The seed's palette colour at a Material tone (0 black .. 100 white), e.g. for a hue slider's track. */
fun SeedColor.tone(tone: Int): Color = lchColor(tone.toFloat(), chroma, hue)
