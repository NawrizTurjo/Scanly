package dev.turjo.paperless.feature.reader

import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.delay

@Composable
internal fun ReaderWindowMode(chrome: ReaderChrome, onImmersiveChanged: (Boolean) -> Unit) {
    val window = LocalActivity.current?.window
    val report by rememberUpdatedState(onImmersiveChanged)
    DisposableEffect(window, chrome.immersive) {
        val controller = if (chrome.immersive && window != null) {
            WindowCompat.getInsetsController(window, window.decorView)
        } else null
        val previousBehavior = controller?.systemBarsBehavior
        val bars = WindowInsetsCompat.Type.systemBars()
        controller?.apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(bars)
        }
        report(chrome.immersive)
        onDispose {
            if (controller != null && previousBehavior != null) {
                controller.show(bars)
                controller.systemBarsBehavior = previousBehavior
            }
            if (chrome.immersive) report(false)
        }
    }
    DisposableEffect(window, chrome.locked) {
        val flag = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        val added = window != null && chrome.locked && window.attributes.flags and flag == 0
        if (added) window.addFlags(flag)
        onDispose { if (added) window.clearFlags(flag) }
    }
}

@Composable
internal fun ReaderLockIndicator(reveal: Int, modifier: Modifier, onUnlock: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    var visible by remember { mutableStateOf(true) }
    LaunchedEffect(reveal, pressed) {
        visible = true
        if (!pressed) {
            delay(2_000)
            visible = false
        }
    }
    if (visible) {
        Surface(
            modifier.padding(12.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.76f),
        ) {
            Box(
                Modifier.size(48.dp).combinedClickable(
                    interactionSource = interaction,
                    indication = null,
                    role = Role.Button,
                    onLongClickLabel = stringResource(R.string.reader_unlock_full_screen),
                    onLongClick = onUnlock,
                    onClick = { },
                ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(R.drawable.reader_ic_lock),
                    contentDescription = stringResource(R.string.reader_lock_full_screen),
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}
