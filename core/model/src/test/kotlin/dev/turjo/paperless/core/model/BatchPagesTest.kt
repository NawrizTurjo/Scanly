package dev.turjo.paperless.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class BatchPagesTest {
    @Test fun reversesBacksAndInterleavesPairs() {
        assertEquals(
            DuplexResult(listOf("F1", "B1", "F2", "B2", "F3", "B3"), false),
            interleaveDuplex(listOf("F1", "F2", "F3"), listOf("B3", "B2", "B1")),
        )
    }

    @Test fun mismatchedCountsPreserveScanOrder() {
        assertEquals(DuplexResult(listOf(1, 2, 4), true), interleaveDuplex(listOf(1, 2), listOf(4)))
        assertEquals(DuplexResult(listOf(4, 3), true), interleaveDuplex(emptyList(), listOf(4, 3)))
        assertEquals(DuplexResult(listOf(1, 2), true), interleaveDuplex(listOf(1, 2), emptyList()))
    }

    @Test fun emptyDuplexBatchMatches() {
        assertEquals(DuplexResult(emptyList<Int>(), false), interleaveDuplex<Int>(emptyList(), emptyList()))
    }

    @Test fun emptyAndAllBlankInputsHaveNoGroups() {
        for (duplex in listOf(false, true)) {
            assertEquals(emptyList<PageGroup>(), splitGroups(emptyList(), duplex))
            assertEquals(emptyList<PageGroup>(), split("___", duplex))
        }
    }

    @Test fun simplexTrimsEdgesAndCollapsesSeparatorRuns() {
        assertEquals(
            listOf(PageGroup(listOf("2", "3"), listOf("4", "5")), PageGroup(listOf("6"), emptyList())),
            split("__xx__x_", duplex = false),
        )
    }

    @Test fun duplexDropsBlankSidesAndSplitsAtBlankSheets() {
        assertEquals(
            listOf(
                PageGroup(listOf("2", "5"), listOf("3", "4", "6", "7", "8", "9")),
                PageGroup(listOf("10", "11"), emptyList()),
            ),
            split("__x__x____xx__", duplex = true),
        )
    }

    @Test fun oddDuplexInputTreatsFinalPageAsOneSided() {
        assertEquals(listOf(PageGroup(listOf("0", "2"), listOf("1"))), split("x_x", duplex = true))
        assertEquals(listOf(PageGroup(listOf("0", "1"), emptyList())), split("xx_", duplex = true))
    }

    private fun split(pattern: String, duplex: Boolean) =
        splitGroups(pattern.mapIndexed { index, side -> PageBlankInfo(index.toString(), side == '_') }, duplex)
}
