package dev.turjo.paperless.core.designsystem

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun FirstRunTip(
    key: String,
    text: String,
    visible: Boolean,
    modifier: Modifier = Modifier,
    alignment: Alignment = Alignment.BottomCenter,
) {
    if (!visible) return

    val context = LocalContext.current
    var tipStore by remember(context, key) { mutableStateOf<TipStore?>(null) }
    LaunchedEffect(context, key) {
        val unseen = withContext(Dispatchers.IO) {
            TipStore(context).takeUnless { it.seen(key) }
        } ?: return@LaunchedEffect
        delay(400)
        tipStore = unseen
    }
    val store = tipStore ?: return
    val dismiss = {
        store.markSeen(key)
        tipStore = null
    }

    LaunchedEffect(store, key) {
        delay(6_000)
        dismiss()
    }

    Box(Modifier.fillMaxSize().then(modifier)) {
        Popup(
            alignment = alignment,
            onDismissRequest = dismiss,
            properties = PopupProperties(focusable = false, dismissOnBackPress = false),
        ) {
            val alpha = remember { Animatable(0f) }
            LaunchedEffect(Unit) { alpha.animateTo(1f) }
            val colors = MaterialTheme.colorScheme
            Surface(
                modifier = Modifier.widthIn(max = 280.dp)
                    .graphicsLayer { this.alpha = alpha.value },
                shape = RoundedCornerShape(16.dp),
                color = colors.inverseSurface,
                contentColor = colors.inverseOnSurface,
                shadowElevation = 4.dp,
            ) {
                Column(Modifier.padding(start = 16.dp, top = 12.dp, end = 12.dp)) {
                    Text(
                        text,
                        Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        fontSize = 14.sp,
                    )
                    TextButton(
                        onClick = dismiss,
                        modifier = Modifier.align(Alignment.End),
                        colors = ButtonDefaults.textButtonColors(contentColor = colors.inversePrimary),
                    ) {
                        Text(stringResource(R.string.tip_got_it))
                    }
                }
            }
        }
    }
}
