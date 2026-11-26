package dev.turjo.paperless.core.designsystem

import android.content.res.Configuration
import android.view.WindowManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.glass.GlassStyle
import dev.chrisbanes.haze.glass.OpticalSizeValue
import dev.chrisbanes.haze.glass.hazeGlass
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

/**
 * The optional glass look (Settings → Appearance → Glass effect). It sits on top of whatever
 * colours are active (dynamic, accent, AMOLED): glass takes its tint from the current scheme.
 *
 * - Floating surfaces (top and bottom bars, toolbars) use real Haze glass over the screen content.
 * - Cards and tiles get a light version: a see-through container and a highlighted edge. A blur of
 *   the smooth background gradient would look the same and cost frames while scrolling.
 * - Dialogs, sheets and menus blur the window behind them (Android's cross-window blur).
 * With glass off, every helper here returns its input unchanged.
 */
val LocalGlassEnabled = staticCompositionLocalOf { false }

/** True when the active scheme is AMOLED black: glass then keeps pure black backgrounds. */
internal val LocalGlassOnBlack = staticCompositionLocalOf { false }

/** The glass source of the nearest [ScanlyScaffold] (or a screen that sets one up itself). */
val LocalGlassState = compositionLocalOf<HazeState?> { null }

/** Bottom space that an outer glass bar (the app's navigation bar) covers. Inner screens scroll under it. */
val LocalGlassBottomInset = compositionLocalOf { 0.dp }

/**
 * Sets up a glass source for screens that lay out their own overlays (the reader). Mark the
 * content with [glassContent] and the overlays with [glassSurface] or [glassOverlay].
 */
@Composable
fun GlassHost(content: @Composable () -> Unit) {
    val state = rememberHazeState()
    CompositionLocalProvider(LocalGlassState provides state.takeIf { LocalGlassEnabled.current }, content = content)
}

/** Marks this as the content that glass overlays of the nearest [GlassHost] refract. */
@Composable
fun Modifier.glassContent(): Modifier = glassSource(LocalGlassState.current, zIndex = 1f)

/** Glass when it's on; otherwise the given [fallback] background. */
@Composable
fun Modifier.glassOverlay(fallback: Color, cornerRadius: Dp = 0.dp): Modifier =
    if (LocalGlassEnabled.current && LocalGlassState.current != null) glassSurface(cornerRadius)
    else background(fallback, RoundedCornerShape(cornerRadius))

/** Marks [state]'s content as what glass surfaces refract. Higher [zIndex] draws in front. */
fun Modifier.glassSource(state: HazeState?, zIndex: Float = 0f): Modifier =
    if (state == null) this else hazeSource(state, zIndex)

/**
 * Real glass for a floating surface. [cornerRadius] 0 is a plain rectangle (bars); larger values
 * round the glass (pills, floating toolbars).
 */
@Composable
fun Modifier.glassSurface(cornerRadius: Dp = 0.dp, state: HazeState? = LocalGlassState.current): Modifier {
    if (!LocalGlassEnabled.current || state == null) return this
    val tint = glassTint()
    val base = if (LocalGlassOnBlack.current) Color.Black else MaterialTheme.colorScheme.background
    val style = remember(cornerRadius, tint, base) {
        // Colours and shapes scrolling underneath should show through, but not text that competes
        // with the bar's labels: a 12 dp blur with a thin tint sits between Regular's full diffusion
        // (content almost hidden) and Clear (titles over readable text).
        GlassStyle.regular.then {
            shape(RoundedCornerShape(cornerRadius))
            // Opaque base: otherwise the sharp content under the glass shows through it.
            backgroundColor(base)
            tint(tint)
            optics { copy(blurRadius = OpticalSizeValue.Fixed(12.dp)) }
            // Neutral tone. Regular lifts the light response (whitePoint 0.55) and darkens the dark
            // one (-0.22), which drew a visible band between an empty bar and the page under it.
            whitePoint(0f)
            contrast(0f)
            chromaMultiplier(1f)
        }
    }
    return hazeGlass(input = HazeInput.Backdrop(state), style = style)
}

/** The tint laid over glass so labels stay readable over busy scans. */
@Composable
@ReadOnlyComposable
fun glassTint(): Color {
    val colors = MaterialTheme.colorScheme
    return when {
        // Kept thin: a heavier tint gave the bars a visible colour band over the page even with
        // nothing scrolled under them. The blur does the work of keeping labels readable.
        LocalGlassOnBlack.current -> Color.Black.copy(alpha = 0.15f)
        colors.surface.luminance() > 0.5f -> colors.surface.copy(alpha = 0.08f)
        else -> colors.surface.copy(alpha = 0.1f)
    }
}

/**
 * Hosts a bar on glass: the bar's own container colours become transparent so the glass shows,
 * while its content colours stay as they are. With glass off it draws [content] unchanged.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun GlassBar(modifier: Modifier = Modifier, cornerRadius: Dp = 0.dp, content: @Composable BoxScope.() -> Unit) {
    if (!LocalGlassEnabled.current || LocalGlassState.current == null) {
        Box(modifier, content = content)
        return
    }
    Box(modifier.glassSurface(cornerRadius)) {
        MaterialExpressiveTheme(
            colorScheme = MaterialTheme.colorScheme.withClearContainers(),
            motionScheme = MotionScheme.expressive(),
            shapes = MaterialTheme.shapes,
            typography = MaterialTheme.typography,
        ) { content() }
    }
}

private fun ColorScheme.withClearContainers() = copy(
    surface = Color.Transparent,
    surfaceContainerLowest = Color.Transparent,
    surfaceContainerLow = Color.Transparent,
    surfaceContainer = Color.Transparent,
    surfaceContainerHigh = Color.Transparent,
    surfaceContainerHighest = Color.Transparent,
)

/** A card or tile container: see-through over the glass background, unchanged without glass. */
@Composable
@ReadOnlyComposable
fun glassContainer(color: Color): Color =
    if (!LocalGlassEnabled.current) color
    else color.copy(alpha = if (LocalGlassOnBlack.current) 0.6f else 0.5f)

/** The highlighted edge that makes a see-through card read as glass. No-op without glass. */
@Composable
fun Modifier.glassEdge(shape: Shape): Modifier {
    if (!LocalGlassEnabled.current) return this
    val light = MaterialTheme.colorScheme.surface.luminance() > 0.5f
    val brush = remember(light) {
        Brush.linearGradient(
            if (light) listOf(Color.White.copy(alpha = 0.9f), Color.White.copy(alpha = 0.25f), Color.Black.copy(alpha = 0.08f))
            else listOf(Color.White.copy(alpha = 0.32f), Color.White.copy(alpha = 0.06f), Color.White.copy(alpha = 0.14f)),
        )
    }
    return border(BorderStroke(1.dp, brush), shape)
}

/**
 * The background behind glass screens: soft colour pools from the theme, so glass has something
 * to refract. AMOLED keeps pure black.
 */
@Composable
fun Modifier.glassBackground(base: Color): Modifier {
    if (!LocalGlassEnabled.current) return this
    if (LocalGlassOnBlack.current) return background(base)
    val colors = MaterialTheme.colorScheme
    val first = colors.primaryContainer
    val second = colors.tertiaryContainer
    return drawBehind {
        drawRect(base)
        drawRect(
            Brush.radialGradient(
                listOf(first.copy(alpha = 0.7f), Color.Transparent),
                center = Offset(size.width * 0.1f, size.height * 0.12f),
                radius = size.maxDimension * 0.55f,
            ),
        )
        drawRect(
            Brush.radialGradient(
                listOf(second.copy(alpha = 0.55f), Color.Transparent),
                center = Offset(size.width * 0.95f, size.height * 0.85f),
                radius = size.maxDimension * 0.6f,
            ),
        )
    }
}

/**
 * Inside a dialog, sheet or menu window: blur what's behind the window. Android drops the blur
 * when the device or battery saver turns cross-window blur off; the usual dim stays.
 */
@Composable
fun BlurBehindWindow(radius: Dp = 28.dp, dimAmount: Float = 0.2f) {
    if (!LocalGlassEnabled.current) return
    val view = LocalView.current
    val pixels = with(LocalDensity.current) { radius.roundToPx() }
    DisposableEffect(view, pixels) {
        val root = view.rootView
        val params = root.layoutParams as? WindowManager.LayoutParams
        val windowManager = view.context.getSystemService(WindowManager::class.java)
        if (params != null && windowManager != null && windowManager.isCrossWindowBlurEnabled) {
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
            params.blurBehindRadius = pixels
            if (params.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND != 0) {
                params.dimAmount = minOf(params.dimAmount, dimAmount)
            }
            runCatching { windowManager.updateViewLayout(root, params) }
        }
        onDispose { }
    }
}

/**
 * Haze picks its light or dark glass from the configuration's night bit. The app can override the
 * system theme, so pass the app's choice down.
 */
@Composable
internal fun ProvideGlass(enabled: Boolean, dark: Boolean, onBlack: Boolean, content: @Composable () -> Unit) {
    val configuration = LocalConfiguration.current
    val night = configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    val adjusted = if (!enabled || night == dark) configuration else remember(configuration, dark) {
        Configuration(configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        }
    }
    CompositionLocalProvider(
        LocalGlassEnabled provides enabled,
        LocalGlassOnBlack provides (enabled && onBlack),
        LocalConfiguration provides adjusted,
        content = content,
    )
}

/**
 * Makes plain `surface` containers inside [content] transparent (list rows inside a card or glass
 * group), leaving every other colour as it is.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ProvideClearSurface(content: @Composable () -> Unit) {
    MaterialExpressiveTheme(
        colorScheme = MaterialTheme.colorScheme.copy(surface = Color.Transparent),
        motionScheme = MotionScheme.expressive(),
        shapes = MaterialTheme.shapes,
        typography = MaterialTheme.typography,
        content = content,
    )
}
