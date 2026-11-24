package dev.turjo.paperless.feature.editor

import dev.turjo.paperless.core.imaging.CardRect
import dev.turjo.paperless.core.imaging.IdCardComposer
import dev.turjo.paperless.core.imaging.IdCardComposer.PAGE_HEIGHT
import dev.turjo.paperless.core.imaging.IdCardComposer.PAGE_WIDTH
import kotlin.math.abs

internal data class CardSnap(val rect: CardRect, val guideX: Float?, val guideY: Float?)

internal fun snapCard(rect: CardRect, other: CardRect, threshold: Float): CardSnap {
    return snapCard(rect, listOf(other), threshold)
}

internal fun snapCard(rect: CardRect, others: List<CardRect>, threshold: Float): CardSnap {
    val center = PAGE_WIDTH / 2f
    val horizontal = listOf((center - rect.width / 2f) to center) + others.flatMap { other ->
        listOf(
            other.left.toFloat() to other.left.toFloat(),
            (other.right - rect.width).toFloat() to other.right.toFloat(),
        )
    }
    val gap = IdCardComposer.gapPixels(6)
    val vertical = others.flatMap { other ->
        val below = (other.bottom + gap).toFloat()
        val above = (other.top - gap - rect.height).toFloat()
        listOf(below to below, above to above + rect.height)
    }
    val x = horizontal.filter { (left, _) -> left in 0f..(PAGE_WIDTH - rect.width).toFloat() }
        .minByOrNull { (left, _) -> abs(left - rect.left) }
        ?.takeIf { (left, _) -> abs(left - rect.left) <= threshold }
    val y = vertical.filter { (top, _) -> top in 0f..(PAGE_HEIGHT - rect.height).toFloat() }
        .minByOrNull { (top, _) -> abs(top - rect.top) }
        ?.takeIf { (top, _) -> abs(top - rect.top) <= threshold }
    return CardSnap(
        rect.copy(left = x?.first?.toInt() ?: rect.left, top = y?.first?.toInt() ?: rect.top),
        x?.second, y?.second,
    )
}
