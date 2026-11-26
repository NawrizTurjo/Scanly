package dev.turjo.paperless.core.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.turjo.paperless.core.model.AccentColor
import dev.turjo.paperless.core.model.CustomTheme
import dev.turjo.paperless.core.model.ThemeMode

internal val LightColors = lightColorScheme(
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

internal val DarkColors = darkColorScheme(
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

fun colorSchemeFor(
    dark: Boolean,
    accent: AccentColor,
    amoled: Boolean,
    custom: CustomTheme = CustomTheme(),
): ColorScheme {
    val colors = accentPalette(dark, accent, custom)
    return if (dark && amoled) colors.withAmoledBlack() else colors
}

private fun ColorScheme.withAmoledBlack() = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceDim = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF0A0A0A),
    surfaceContainer = Color(0xFF121212),
    surfaceContainerHigh = Color(0xFF1A1A1A),
    surfaceContainerHighest = Color(0xFF222222),
    surfaceBright = Color(0xFF292929),
)

private val scanlyShapes = Shapes(
    medium = RoundedCornerShape(24.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

private val scanlyTypography = Typography().let {
    Typography(
        headlineMedium = it.headlineMedium.copy(fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight.SemiBold),
        titleLarge = it.titleLarge.copy(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold),
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ScanlyTheme(
    themeMode: ThemeMode,
    dynamicColor: Boolean,
    amoledBlack: Boolean = false,
    accent: AccentColor = AccentColor.BLUE,
    glass: Boolean = LocalGlassEnabled.current,
    customTheme: CustomTheme = CustomTheme(),
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val colors = when {
        dynamicColor && dark -> dynamicDarkColorScheme(context).let {
            if (amoledBlack) it.withAmoledBlack() else it
        }
        dynamicColor -> dynamicLightColorScheme(context)
        else -> colorSchemeFor(dark, accent, amoledBlack, customTheme)
    }
    MaterialExpressiveTheme(
        colorScheme = colors,
        motionScheme = MotionScheme.expressive(),
        shapes = scanlyShapes,
        typography = scanlyTypography,
    ) { ProvideGlass(glass, dark, onBlack = dark && amoledBlack, content) }
}
