package dev.turjo.paperless.core.pdf

import android.graphics.Point
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.graphics.pdf.models.selection.SelectionBoundary
import androidx.annotation.RequiresApi
import dev.turjo.paperless.core.model.PageLink
import dev.turjo.paperless.core.model.PageRect
import dev.turjo.paperless.core.model.TextMatch
import kotlin.math.roundToInt

@RequiresApi(35)
internal object PlatformPageContent {
    fun search(page: PdfRenderer.Page, query: String): List<TextMatch> {
        val matches = page.searchText(query)
        if (matches.isEmpty()) return emptyList()
        val text = text(page)
        return matches.mapNotNull {
            val start = it.textStartIndex
            val end = start + query.length
            val wordContinues = (query.first().isLetterOrDigit() && text.joinsTokenAt(start)) ||
                (query.last().isLetterOrDigit() && text.joinsTokenAt(end))
            if (wordContinues) null else TextMatch(page.index, it.bounds.map(RectF::toPageRect))
        }
    }

    fun text(page: PdfRenderer.Page): String = page.textContents.joinToString("\n") { it.text }

    fun select(page: PdfRenderer.Page, start: PointF, end: PointF): Pair<String, List<PageRect>>? {
        val selection = page.selectContent(start.toBoundary(), end.toBoundary()) ?: return null
        val contents = selection.selectedTextContents
        val text = contents.joinToString("\n") { it.text }
        return if (text.isEmpty()) null else text to contents.flatMap { it.bounds.map(RectF::toPageRect) }
    }

    fun links(page: PdfRenderer.Page): List<PageLink> =
        page.gotoLinks.map { PageLink(it.bounds.map(RectF::toPageRect), it.destination.pageNumber, null) } +
            page.linkContents.map { PageLink(it.bounds.map(RectF::toPageRect), null, it.uri.toString()) }

    private fun PointF.toBoundary() = SelectionBoundary(Point(x.roundToInt(), y.roundToInt()))

    private fun String.joinsTokenAt(index: Int): Boolean {
        val before = getOrNull(index - 1) ?: return false
        val after = getOrNull(index) ?: return false
        return before.isLetterOrDigit() &&
            (after.isLetterOrDigit() || after == '-' && getOrNull(index + 1)?.isLetterOrDigit() == true) ||
            after.isLetterOrDigit() && before == '-' && getOrNull(index - 2)?.isLetterOrDigit() == true
    }
}

private fun RectF.toPageRect() = PageRect(left, top, right, bottom)
