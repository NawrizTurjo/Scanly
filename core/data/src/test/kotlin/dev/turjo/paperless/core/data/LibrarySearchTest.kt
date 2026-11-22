package dev.turjo.paperless.core.data

import java.time.LocalDate
import java.time.Month
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibrarySearchTest {
    private val today = LocalDate.of(2026, 12, 1)

    @Test
    fun matchesTagNamesIgnoringCase() {
        val search = parseLibrarySearch("physics", LocalDate.of(2026, 1, 1))
        assertEquals(true, search.matchesText("Notes", "School", listOf("Physics")))
        assertEquals(false, search.matchesText("Notes", "School", listOf("Chemistry")))
    }

    @Test
    fun parsesRelativeAndCalendarDateTokens() {
        val result = parseLibrarySearch("ToDaY yesterday 2024-02-29", today)

        assertEquals("", result.text)
        assertEquals(setOf(today, today.minusDays(1), LocalDate.of(2024, 2, 29)), result.dates)
        assertTrue(result.matchesDate(today.minusDays(1)))
        assertFalse(result.matchesDate(today.minusDays(2)))
    }

    @Test
    fun parsesMonthNameAndRetainsSearchText() {
        val result = parseLibrarySearch("  Train   NOVEMBER   Receipt ", today)

        assertEquals("Train Receipt", result.text)
        assertEquals(setOf(Month.NOVEMBER), result.months)
        assertTrue(result.matchesDate(LocalDate.of(2025, 11, 3)))
        assertFalse(result.matchesDate(today))
    }

    @Test
    fun invalidDatesAndPartialKeywordsStayInTheText() {
        val result = parseLibrarySearch("2025-02-29 todays Novemberish", today)

        assertEquals("2025-02-29 todays Novemberish", result.text)
        assertTrue(result.matchesDate(today))
    }
}
