package dev.turjo.paperless.feature.settings

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

internal sealed interface NotesBlock {
    val text: AnnotatedString
    /** True when a blank line came before this block. */
    val gapBefore: Boolean

    data class Heading(override val text: AnnotatedString, val level: Int, override val gapBefore: Boolean) : NotesBlock
    data class Item(override val text: AnnotatedString, val marker: String, override val gapBefore: Boolean) : NotesBlock
    data class Paragraph(override val text: AnnotatedString, override val gapBefore: Boolean) : NotesBlock
}

private val headingLine = Regex("^(#{1,6})\\s+(.*)$")
private val bulletLine = Regex("^[-*+]\\s+(.*)$")
private val numberedLine = Regex("^(\\d+)[.)]\\s+(.*)$")

/**
 * Parses the small Markdown subset GitHub release notes use: `#` headings, `-`/`*` bullets,
 * numbered items, `**bold**`, `*italic*`/`_italic_`, `` `code` `` and `[text](url)` (shown as
 * text). Anything else stays as written.
 */
internal fun releaseNotesMarkdown(markdown: String): List<NotesBlock> {
    val blocks = mutableListOf<NotesBlock>()
    var gap = false
    for (raw in markdown.replace("\r\n", "\n").replace('\r', '\n').lines()) {
        val line = raw.trim()
        if (line.isEmpty()) {
            gap = blocks.isNotEmpty()
            continue
        }
        val heading = headingLine.find(line)
        val bullet = bulletLine.find(line)
        val numbered = numberedLine.find(line)
        blocks += when {
            heading != null -> NotesBlock.Heading(
                inline(heading.groupValues[2].trimEnd('#', ' ')), heading.groupValues[1].length, gap,
            )
            bullet != null -> NotesBlock.Item(inline(bullet.groupValues[1]), "\u2022", gap)
            numbered != null -> NotesBlock.Item(inline(numbered.groupValues[2]), "${numbered.groupValues[1]}.", gap)
            else -> NotesBlock.Paragraph(inline(line), gap)
        }
        gap = false
    }
    return blocks
}

private fun inline(text: String): AnnotatedString = buildAnnotatedString { appendInline(text) }

private val inlinePattern = Regex(
    "\\*\\*(.+?)\\*\\*|__(.+?)__|`([^`]+)`|\\[([^\\]]+)]\\(([^)]*)\\)|(?<![\\w*])\\*(?!\\s)(.+?)(?<!\\s)\\*(?![\\w*])|(?<!\\w)_(?!\\s)(.+?)(?<!\\s)_(?!\\w)",
)

private fun AnnotatedString.Builder.appendInline(text: String) {
    var index = 0
    for (match in inlinePattern.findAll(text)) {
        append(text.substring(index, match.range.first))
        val g = match.groupValues
        when {
            g[1].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { appendInline(g[1]) }
            g[2].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { appendInline(g[2]) }
            g[3].isNotEmpty() -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(g[3]) }
            g[4].isNotEmpty() -> appendInline(g[4])
            g[6].isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { appendInline(g[6]) }
            g[7].isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { appendInline(g[7]) }
        }
        index = match.range.last + 1
    }
    append(text.substring(index))
}
