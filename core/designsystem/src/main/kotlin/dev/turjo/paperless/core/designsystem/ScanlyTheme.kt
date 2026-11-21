package dev.turjo.paperless.core.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import dev.turjo.paperless.core.model.ThemeMode

private val LightColors = lightColorScheme(
    primary = Color(0xFF375D87),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD2E4FF),
    onPrimaryContainer = Color(0xFF001C39),
    secondary = Color(0xFF545F71),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD8E3F8),
    onSecondaryContainer = Color(0xFF111C2B),
    tertiary = Color(0xFF6D5677),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF7D9FF),
    onTertiaryContainer = Color(0xFF271431),
    background = Color(0xFFF8F9FF),
    surface = Color(0xFFF8F9FF),
    onBackground = Color(0xFF191C20),
    onSurface = Color(0xFF191C20),
    surfaceVariant = Color(0xFFDFE3EB),
    onSurfaceVariant = Color(0xFF43474E),
    outline = Color(0xFF737780),
    outlineVariant = Color(0xFFC3C7D0),
    surfaceTint = Color(0xFF375D87),
    inverseSurface = Color(0xFF2E3036),
    inverseOnSurface = Color(0xFFEFF0F7),
    inversePrimary = Color(0xFFA1C9F9),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF2F3FA),
    surfaceContainer = Color(0xFFECEEF5),
    surfaceContainerHigh = Color(0xFFE6E8EF),
    surfaceContainerHighest = Color(0xFFE1E2E9),
    surfaceBright = Color(0xFFF8F9FF),
    surfaceDim = Color(0xFFD8DAE1),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA1C9F9),
    onPrimary = Color(0xFF003258),
    primaryContainer = Color(0xFF20456D),
    onPrimaryContainer = Color(0xFFD2E4FF),
    secondary = Color(0xFFBCC7DB),
    onSecondary = Color(0xFF263141),
    secondaryContainer = Color(0xFF3C4759),
    onSecondaryContainer = Color(0xFFD8E3F8),
    tertiary = Color(0xFFD9BDE4),
    onTertiary = Color(0xFF3D2947),
    tertiaryContainer = Color(0xFF553F5E),
    onTertiaryContainer = Color(0xFFF7D9FF),
    background = Color(0xFF111318),
    surface = Color(0xFF111318),
    onBackground = Color(0xFFE1E2E9),
    onSurface = Color(0xFFE1E2E9),
    surfaceVariant = Color(0xFF43474E),
    onSurfaceVariant = Color(0xFFC3C7D0),
    outline = Color(0xFF8D919A),
    outlineVariant = Color(0xFF43474E),
    surfaceTint = Color(0xFFA1C9F9),
    inverseSurface = Color(0xFFE1E2E9),
    inverseOnSurface = Color(0xFF2E3036),
    inversePrimary = Color(0xFF375D87),
    surfaceContainerLowest = Color(0xFF0B0E13),
    surfaceContainerLow = Color(0xFF191C20),
    surfaceContainer = Color(0xFF1D2025),
    surfaceContainerHigh = Color(0xFF272A2F),
    surfaceContainerHighest = Color(0xFF32353A),
    surfaceBright = Color(0xFF37393F),
    surfaceDim = Color(0xFF111318),
)

@Composable
fun ScanlyTheme(
    themeMode: ThemeMode,
    dynamicColor: Boolean,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val colors = when {
        dynamicColor && dark -> dynamicDarkColorScheme(context)
        dynamicColor -> dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}
