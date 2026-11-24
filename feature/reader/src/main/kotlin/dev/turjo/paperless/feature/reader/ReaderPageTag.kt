package dev.turjo.paperless.feature.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

internal fun pageTagLabel(first: Int, last: Int, total: Int): String =
    if (first == last) "$first / $total" else "$first–$last / $total"

@Composable
internal fun ReaderPageTag(first: Int, last: Int, total: Int, scrolling: Boolean, modifier: Modifier) {
    if (total <= 1) return
    val label = pageTagLabel(first, last, total)
    var visible by remember { mutableStateOf(false) }
    var previous by remember { mutableStateOf(label) }
    LaunchedEffect(label, scrolling) {
        if (scrolling || label != previous) visible = true
        previous = label
        if (!scrolling && visible) {
            delay(1_500)
            visible = false
        }
    }
    AnimatedVisibility(visible, modifier, enter = fadeIn(), exit = fadeOut()) {
        Text(
            label,
            Modifier.padding(end = 8.dp)
                .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(50))
                .padding(horizontal = 9.dp, vertical = 4.dp)
                .semantics { hideFromAccessibility() },
            color = Color.White,
            fontSize = 12.sp,
        )
    }
}
