package dev.turjo.paperless.core.designsystem

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FabPosition
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.SheetState
import androidx.compose.material3.contentColorFor
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.PopupProperties
import dev.chrisbanes.haze.rememberHazeState
import androidx.compose.material3.AlertDialog as M3AlertDialog
import androidx.compose.material3.DropdownMenu as M3DropdownMenu
import androidx.compose.material3.ModalBottomSheet as M3ModalBottomSheet

/**
 * [Scaffold] with the optional glass look. With glass off it draws like [Scaffold]. With glass on,
 * the top and bottom bars become glass over the content (which draws behind them, so lists should
 * apply the padding as `contentPadding`), the background gets soft colour pools, and content
 * scrolls under an outer navigation bar ([LocalGlassBottomInset]).
 *
 * The layout tree is the same either way, so switching glass, or [glassSource], never resets the
 * screen's state. Pass `glassSource = false` while content that must not be captured into an
 * offscreen layer is showing (the camera preview's surface).
 */
@Composable
fun ScanlyScaffold(
    modifier: Modifier = Modifier,
    topBar: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    snackbarHost: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    floatingActionButtonPosition: FabPosition = FabPosition.End,
    containerColor: Color = MaterialTheme.colorScheme.background,
    contentColor: Color = contentColorFor(containerColor),
    contentWindowInsets: WindowInsets = ScaffoldDefaults.contentWindowInsets,
    glassSource: Boolean = true,
    /** False when the bottom bar draws its own floating glass (the page viewer's toolbar). */
    bottomBarGlass: Boolean = true,
    /** False keeps a plain background behind glass (the page viewer: nothing should compete with the scan). */
    colourPools: Boolean = true,
    content: @Composable (PaddingValues) -> Unit,
) {
    val glass = LocalGlassEnabled.current && glassSource
    val hazeState = rememberHazeState()
    val state = hazeState.takeIf { glass }
    val outerBottom = if (glass) LocalGlassBottomInset.current else 0.dp
    CompositionLocalProvider(LocalGlassState provides state) {
        Scaffold(
            modifier = modifier,
            topBar = { GlassBar { topBar() } },
            bottomBar = { if (bottomBarGlass) GlassBar { bottomBar() } else bottomBar() },
            snackbarHost = { Box(Modifier.padding(bottom = outerBottom)) { snackbarHost() } },
            floatingActionButton = { Box(Modifier.padding(bottom = outerBottom)) { floatingActionButton() } },
            floatingActionButtonPosition = floatingActionButtonPosition,
            containerColor = if (glass) Color.Transparent else containerColor,
            contentColor = contentColor,
            contentWindowInsets = contentWindowInsets,
        ) { padding ->
            val direction = LocalLayoutDirection.current
            val combined = if (outerBottom == 0.dp) padding else PaddingValues(
                start = padding.calculateStartPadding(direction),
                top = padding.calculateTopPadding(),
                end = padding.calculateEndPadding(direction),
                bottom = padding.calculateBottomPadding() + outerBottom,
            )
            Box(Modifier.fillMaxSize()) {
                Box(
                    Modifier.fillMaxSize()
                        .then(
                            when {
                                !glass -> Modifier
                                colourPools -> Modifier.glassBackground(containerColor)
                                else -> Modifier.background(containerColor)
                            },
                        )
                        .glassSource(state, zIndex = 0f),
                )
                Box(Modifier.fillMaxSize().glassSource(state, zIndex = 1f)) {
                    CompositionLocalProvider(LocalGlassBottomInset provides 0.dp) { content(combined) }
                }
            }
        }
    }
}

/** Material's AlertDialog; with glass on it is see-through over a blurred screen. */
@Composable
fun AlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: @Composable (() -> Unit)? = null,
    icon: @Composable (() -> Unit)? = null,
    title: @Composable (() -> Unit)? = null,
    text: @Composable (() -> Unit)? = null,
    shape: Shape = AlertDialogDefaults.shape,
    containerColor: Color = AlertDialogDefaults.containerColor,
    iconContentColor: Color = AlertDialogDefaults.iconContentColor,
    titleContentColor: Color = AlertDialogDefaults.titleContentColor,
    textContentColor: Color = AlertDialogDefaults.textContentColor,
    tonalElevation: Dp = AlertDialogDefaults.TonalElevation,
    properties: DialogProperties = DialogProperties(),
) {
    val glass = LocalGlassEnabled.current
    M3AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            BlurBehindWindow()
            confirmButton()
        },
        modifier = if (glass) modifier.glassEdge(shape) else modifier,
        dismissButton = dismissButton,
        icon = icon,
        title = title,
        text = text,
        shape = shape,
        containerColor = glassDialogColor(containerColor),
        iconContentColor = iconContentColor,
        titleContentColor = titleContentColor,
        textContentColor = textContentColor,
        tonalElevation = if (glass) 0.dp else tonalElevation,
        properties = properties,
    )
}

/** Material's ModalBottomSheet; with glass on it is see-through over a blurred screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModalBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
    content: @Composable ColumnScope.() -> Unit,
) {
    val containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    M3ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = sheetState,
        containerColor = glassDialogColor(containerColor),
        tonalElevation = 0.dp,
    ) {
        BlurBehindWindow()
        content()
    }
}

/** Material's DropdownMenu; with glass on it is see-through over a blurred screen. */
@Composable
fun DropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset(0.dp, 0.dp),
    scrollState: ScrollState = rememberScrollState(),
    properties: PopupProperties = PopupProperties(focusable = true),
    shape: Shape = MenuDefaults.shape,
    containerColor: Color = MenuDefaults.containerColor,
    border: BorderStroke? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    M3DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        offset = offset,
        scrollState = scrollState,
        properties = properties,
        shape = shape,
        containerColor = glassDialogColor(containerColor),
        border = border,
    ) {
        BlurBehindWindow(radius = 16.dp, dimAmount = 0f)
        content()
    }
}

/** A dialog or sheet container: mostly opaque so text stays readable over the blurred screen. */
@Composable
fun glassDialogColor(color: Color): Color =
    if (!LocalGlassEnabled.current) color
    else color.copy(alpha = if (LocalGlassOnBlack.current) 0.72f else 0.78f)
