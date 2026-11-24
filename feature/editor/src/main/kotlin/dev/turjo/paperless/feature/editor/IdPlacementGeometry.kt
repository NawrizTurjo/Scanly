package dev.turjo.paperless.feature.editor

import dev.turjo.paperless.core.imaging.CardRect
import dev.turjo.paperless.core.imaging.IdCardComposer
import dev.turjo.paperless.core.imaging.IdCardComposer.PAGE_HEIGHT
import dev.turjo.paperless.core.imaging.IdCardComposer.PAGE_WIDTH
import kotlin.math.abs

internal data class CardSnap(val rect: CardRect, val guideX: Float?, val guideY: Float?)

internal fun snapCard(rect: CardRect, other: CardRect, threshold: Float): CardSnap {
    val center = PAGE_WIDTH / 2f
    val horizontal = listOf(
        (center - rect.width / 2f) to center,
        other.left.toFloat() to other.left.toFloat(),
        (other.right - rect.width).toFloat() to other.right.toFloat(),
    )
    val gap = IdCardComposer.gapPixels(6)
    val vertical = listOf(
        (other.bottom + gap).toFloat(),
        (other.top - gap - rect.height).toFloat(),
    )
    val x = horizontal.filter { (left, _) -> left in 0f..(PAGE_WIDTH - rect.width).toFloat() }
        .minByOrNull { (left, _) -> abs(left - rect.left) }
        ?.takeIf { (left, _) -> abs(left - rect.left) <= threshold }
    val y = vertical.filter { it in 0f..(PAGE_HEIGHT - rect.height).toFloat() }
        .minByOrNull { abs(it - rect.top) }?.takeIf { abs(it - rect.top) <= threshold }
    return CardSnap(
        rect.copy(left = x?.first?.toInt() ?: rect.left, top = y?.toInt() ?: rect.top),
        x?.second,
        y?.let { if (it >= other.bottom) it else it + rect.height },
    )
}
