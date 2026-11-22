package dev.turjo.paperless.core.pdf.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NUpTest {
    @Test
    fun imposesEveryLayoutAndFlip() {
        for (n in 1..13) for (layout in NUpLayout.entries) for (flip in Flip.entries) {
            val sheets = NUp.impose(n, layout, flip)
            val inverseFlip = if (flip == Flip.LONG) Flip.SHORT else Flip.LONG
            val other = NUp.impose(n, layout, inverseFlip)
            assertEquals(sheets.map { it.front }, other.map { it.front })
            assertEquals(sheets.map { it.back.reversed() }, other.map { it.back })
            val duplex = sheets.any { it.back.isNotEmpty() }
            val pagesPerSheet = layout.perSide * if (duplex) 2 else 1
            val padded = ((n + pagesPerSheet - 1) / pagesPerSheet) * pagesPerSheet
            assertEquals(padded / pagesPerSheet, sheets.size)
            fun back(sheet: Sheet) = if (flip == Flip.LONG) sheet.back.reversed() else sheet.back
            fun half(sheet: Sheet, column: Int) = listOf(sheet.front[column], back(sheet)[column])
            val readingOrder = when (layout) {
                NUpLayout.CUT_EACH_SHEET -> sheets.flatMap { half(it, 0) + half(it, 1) }
                NUpLayout.CUT_WHOLE_STACK -> listOf(0, 1).flatMap { column ->
                    sheets.flatMap { half(it, column) }
                }
                NUpLayout.BOOKLET -> sheets.flatMap { half(it, 1) } +
                    sheets.asReversed().flatMap { half(it, 0).reversed() }
                else -> sheets.flatMap { it.front }
            }
            assertEquals("$n $layout $flip", (0 until padded).map { it.takeIf { it < n } }, readingOrder)
            assertTrue(sheets.all { it.front.size == layout.perSide })
        }
    }

    @Test
    fun fourPageDuplexMatchesPrintOrder() {
        for (layout in listOf(NUpLayout.CUT_EACH_SHEET, NUpLayout.CUT_WHOLE_STACK)) {
            assertEquals(
                listOf(Sheet(listOf(0, 2), listOf(3, 1))),
                NUp.impose(4, layout, Flip.LONG),
            )
        }
        assertEquals(
            listOf(Sheet(listOf(3, 0), listOf(1, 2))),
            NUp.impose(4, NUpLayout.BOOKLET, Flip.LONG),
        )
    }
}
