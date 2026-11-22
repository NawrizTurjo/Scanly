package dev.turjo.paperless.core.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import dev.turjo.paperless.core.model.AccentColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AccentPalettesTest {
    @Test
    fun blueUsesTheOriginalSchemes() {
        assertSame(LightColors, colorSchemeFor(dark = false, AccentColor.BLUE, amoled = false))
        assertSame(DarkColors, colorSchemeFor(dark = true, AccentColor.BLUE, amoled = false))
    }

    @Test
    fun amoledMakesDarkSurfacesBlackAndPreservesAccent() {
        AccentColor.entries.forEach { accent ->
            val normal = colorSchemeFor(dark = true, accent, amoled = false)
            val amoled = colorSchemeFor(dark = true, accent, amoled = true)
            assertEquals(Color.Black, amoled.background)
            assertEquals(Color.Black, amoled.surface)
            assertEquals(Color.Black, amoled.surfaceDim)
            assertEquals(Color.Black, amoled.surfaceContainerLowest)
            assertEquals(normal.primary, amoled.primary)
            assertEquals(normal.surfaceVariant, amoled.surfaceVariant)
        }
    }

    @Test
    fun amoledLeavesLightSchemesUnchanged() {
        AccentColor.entries.forEach { accent ->
            assertSame(
                colorSchemeFor(dark = false, accent, amoled = false),
                colorSchemeFor(dark = false, accent, amoled = true),
            )
        }
    }

    @Test
    fun foregroundRolesMeetTextContrast() {
        for (accent in AccentColor.entries) {
            for (dark in listOf(false, true)) {
                for (amoled in listOf(false, true)) {
                    val colors = colorSchemeFor(dark, accent, amoled)
                    val pairs = listOf(
                        colors.onPrimary to colors.primary,
                        colors.onPrimaryContainer to colors.primaryContainer,
                        colors.onSecondary to colors.secondary,
                        colors.onSecondaryContainer to colors.secondaryContainer,
                        colors.onTertiary to colors.tertiary,
                        colors.onTertiaryContainer to colors.tertiaryContainer,
                        colors.onBackground to colors.background,
                        colors.onSurface to colors.surface,
                        colors.onSurface to colors.surfaceContainerHighest,
                        colors.onSurfaceVariant to colors.surfaceVariant,
                        colors.inverseOnSurface to colors.inverseSurface,
                        colors.onError to colors.error,
                        colors.onErrorContainer to colors.errorContainer,
                    )
                    for ((foreground, background) in pairs) {
                        val a = foreground.luminance()
                        val b = background.luminance()
                        val contrast = (maxOf(a, b) + 0.05f) / (minOf(a, b) + 0.05f)
                        assertTrue("$accent dark=$dark amoled=$amoled $foreground on $background: $contrast", contrast >= 4.5f)
                    }
                }
            }
        }
    }
}
