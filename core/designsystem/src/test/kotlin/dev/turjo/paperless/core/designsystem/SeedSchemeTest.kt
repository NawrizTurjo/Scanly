package dev.turjo.paperless.core.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SeedSchemeTest {
    private fun contrast(a: Color, b: Color): Float {
        val (hi, lo) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (hi + 0.05f) / (lo + 0.05f)
    }

    @Test fun rolesHaveReadableContrastForManySeeds() {
        for (hue in 0 until 360 step 30) for (chroma in listOf(0f, 16f, 40f, 70f, 100f)) for (dark in listOf(false, true)) {
            val s = seedColorScheme(SeedColor(hue.toFloat(), chroma), dark, SeedColor(360f - hue, 100f - chroma), SeedColor(hue / 2f, chroma))
            val label = "hue $hue chroma $chroma dark $dark"
            assertTrue(label, contrast(s.primary, s.onPrimary) >= 4.5f)
            assertTrue(label, contrast(s.primaryContainer, s.onPrimaryContainer) >= 4.5f)
            assertTrue(label, contrast(s.surface, s.onSurface) >= 7f)
            assertTrue(label, contrast(s.secondaryContainer, s.onSecondaryContainer) >= 4.5f)
            assertTrue(label, contrast(s.tertiary, s.onTertiary) >= 4.5f)
            assertTrue(label, contrast(s.surfaceContainerHighest, s.onSurfaceVariant) >= 4.5f)
        }
    }

    @Test fun tonesFollowLightness() {
        for (tone in listOf(10, 40, 80, 95)) {
            val (lightness, _, _) = colorToLch(lchColor(tone.toFloat(), 50f, 250f))
            assertEquals(tone.toFloat(), lightness, 1f)
        }
    }

    @Test fun aSeedRoundTripsThroughItsColour() {
        val seed = SeedColor.of(Color(0xFF3366CC))
        val back = SeedColor.of(seed.toColor())
        assertEquals(seed.hue, back.hue, 2f)
    }

    @Test fun outOfGamutChromaIsLoweredNotLightness() {
        assertEquals(90f, colorToLch(lchColor(90f, 120f, 280f)).first, 1f)
    }
}
