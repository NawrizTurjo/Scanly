package dev.turjo.paperless.feature.settings

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseNotesMarkdownTest {
    private val notes = "Installs over 1.0 and keeps your library.\r\n\r\n## New\r\n" +
        "- **Fast-scroll in the reader.** Drag the tag.\r\n" +
        "* Second item with `code` and [a link](https://example.com)\r\n" +
        "1. Numbered *italic* and _also italic_\r\n\r\n" +
        "Plain snake_case_name stays and 2 * 3 * 4 stays."

    @Test
    fun parsesBlocks() {
        val blocks = releaseNotesMarkdown(notes)
        assertEquals(6, blocks.size)
        assertTrue(blocks[0] is NotesBlock.Paragraph)
        val heading = blocks[1] as NotesBlock.Heading
        assertEquals("New", heading.text.text)
        assertEquals(2, heading.level)
        assertTrue(heading.gapBefore)
        val first = blocks[2] as NotesBlock.Item
        assertEquals("•", first.marker)
        assertEquals("Fast-scroll in the reader. Drag the tag.", first.text.text)
        assertFalse(first.gapBefore)
        assertEquals("Second item with code and a link", blocks[3].text.text)
        val numbered = blocks[4] as NotesBlock.Item
        assertEquals("1.", numbered.marker)
        assertEquals("Numbered italic and also italic", numbered.text.text)
        assertEquals("Plain snake_case_name stays and 2 * 3 * 4 stays.", blocks[5].text.text)
        assertTrue(blocks[5].gapBefore)
    }

    @Test
    fun stylesInlineSpans() {
        val blocks = releaseNotesMarkdown(notes)
        val bold = blocks[2].text.spanStyles.single()
        assertEquals(FontWeight.Bold, bold.item.fontWeight)
        assertEquals("Fast-scroll in the reader.", blocks[2].text.text.substring(bold.start, bold.end))
        assertEquals(FontFamily.Monospace, blocks[3].text.spanStyles.single().item.fontFamily)
        val italics = blocks[4].text.spanStyles
        assertEquals(2, italics.size)
        assertTrue(italics.all { it.item.fontStyle == FontStyle.Italic })
        assertTrue(blocks[5].text.spanStyles.isEmpty())
    }

    @Test
    fun blankNotesGiveNoBlocks() {
        assertTrue(releaseNotesMarkdown(" \n\n ").isEmpty())
    }
}
