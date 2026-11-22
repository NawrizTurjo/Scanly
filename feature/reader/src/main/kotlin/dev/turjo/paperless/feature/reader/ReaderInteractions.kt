package dev.turjo.paperless.feature.reader

import dev.turjo.paperless.core.model.OutlineNode
import dev.turjo.paperless.core.model.PageRect
import dev.turjo.paperless.core.model.TextMatch

internal data class PageTransform(
    val crop: PageRect,
    val width: Float,
    val height: Float,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
) {
    // Engine rectangles already use the orientation of the rendered page.
    private val scaleX = width / (crop.right - crop.left)
    private val scaleY = height / (crop.bottom - crop.top)

    fun mapX(x: Float) = (x - crop.left) * scaleX + offsetX

    fun mapY(y: Float) = (y - crop.top) * scaleY + offsetY

    fun map(rect: PageRect) = PageRect(
        mapX(rect.left),
        mapY(rect.top),
        mapX(rect.right),
        mapY(rect.bottom),
    )

    fun unmap(x: Float, y: Float) =
        (x - offsetX) / scaleX + crop.left to (y - offsetY) / scaleY + crop.top
}

internal fun PageRect.union(other: PageRect) = PageRect(
    minOf(left, other.left), minOf(top, other.top), maxOf(right, other.right), maxOf(bottom, other.bottom),
)

internal enum class SearchStatus(val label: String) {
    PENDING("Searchingâ€¦"), NO_MATCHES("No matches"), NO_TEXT("No text layer"), ERROR("Could not search this PDF"),
}

internal data class SearchState(
    val query: String = "",
    val matches: List<TextMatch> = emptyList(),
    val index: Int = 0,
    val status: SearchStatus? = null,
) {
    val current get() = matches.getOrNull(index)
    fun step(delta: Int) = if (matches.isEmpty()) this else copy(index = (index + delta).mod(matches.size))
}

internal data class LinkHistory(val pages: List<Int> = emptyList()) {
    val previous get() = pages.lastOrNull()
    fun follow(page: Int) = copy(pages = pages + page)
    fun back() = copy(pages = pages.dropLast(1))
}

internal data class OutlineRow(val title: String, val page: Int, val level: Int)

internal fun List<OutlineNode>.flattenOutline(level: Int = 0): List<OutlineRow> = flatMap {
    listOf(OutlineRow(it.title, it.page, level)) + it.children.flattenOutline(level + 1)
}

internal fun List<OutlineRow>.toOutline(): List<OutlineNode> {
    var index = 0
    fun children(level: Int): List<OutlineNode> = buildList {
        while (index < this@toOutline.size && this@toOutline[index].level >= level) {
            val row = this@toOutline[index++]
            add(OutlineNode(row.title, row.page, children(level + 1)))
        }
    }
    return children(0)
}

internal fun List<OutlineRow>.branchEnd(index: Int): Int =
    (index + 1 until size).firstOrNull { this[it].level <= this[index].level } ?: size

internal fun List<OutlineRow>.changeLevel(index: Int, delta: Int): List<OutlineRow> {
    val level = this[index].level
    val previousSibling = (index - 1 downTo 0).firstOrNull { this[it].level <= level }
    if (delta > 0 && previousSibling?.let { this[it].level } != level || delta < 0 && level == 0) return this
    val end = branchEnd(index)
    val branch = subList(index, end).map { it.copy(level = it.level + delta) }
    return toMutableList().apply {
        subList(index, end).clear()
        val destination = if (delta < 0) {
            (index until size).firstOrNull { this[it].level < level } ?: size
        } else index
        addAll(destination, branch)
    }
}

internal fun List<OutlineRow>.moveBranch(index: Int, delta: Int): List<OutlineRow> {
    val level = this[index].level
    val end = branchEnd(index)
    val sibling = if (delta < 0) (index - 1 downTo 0).firstOrNull { this[it].level <= level } else end
    if (sibling == null || sibling >= size || this[sibling].level != level) return this
    val branch = subList(index, end).toList()
    val destination = if (delta < 0) sibling else branchEnd(sibling) - branch.size
    return toMutableList().apply {
        subList(index, end).clear()
        addAll(destination, branch)
    }
}

internal fun List<OutlineRow>.mergeSuggestions(suggestions: List<OutlineRow>): List<OutlineRow> {
    val existing = map { it.title.trim().lowercase() to it.page }.toMutableSet()
    return this + suggestions.toOutline().flattenOutline().filter {
        existing.add(it.title.trim().lowercase() to it.page)
    }.toOutline().flattenOutline()
}

internal enum class CropMode(val label: String) { OFF("Off"), PAGE("Per page"), UNIFORM("Uniform") }
