package dev.turjo.paperless.core.designsystem

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale

val LocalSharedTransitionScope = staticCompositionLocalOf<SharedTransitionScope?> { null }
val LocalNavAnimatedScope = staticCompositionLocalOf<AnimatedVisibilityScope?> { null }

private val pageBoundsTransform = BoundsTransform { _, _ ->
    tween(durationMillis = 300, easing = FastOutSlowInEasing)
}

@Composable
fun Modifier.sharedPage(key: String): Modifier {
    val sharedScope = LocalSharedTransitionScope.current ?: return this
    val navScope = LocalNavAnimatedScope.current ?: return this
    if (rememberCoroutineScope().coroutineContext[MotionDurationScale]?.scaleFactor == 0f) return this
    // The Fit/Crop image is measured against the moving bounds, preserving its aspect ratio.
    return with(sharedScope) {
        sharedElement(
            sharedContentState = rememberSharedContentState(key),
            animatedVisibilityScope = navScope,
            boundsTransform = pageBoundsTransform,
        )
    }
}
