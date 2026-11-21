package dev.turjo.paperless.core.model

import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class AutoNameTest {
    private val now = LocalDateTime.of(2026, 1, 9, 7, 5)

    @Test
    fun formatsDefaultPattern() {
        assertEquals("Scan 2026-01-09 07.05", formatAutoName(UserSettings().autoNamePattern, now, 1))
    }

    @Test
    fun replacesEveryKnownTokenAndPreservesOtherText() {
        assertEquals("42 {other} 07.05 42", formatAutoName("{n} {other} {time} {n}", now, 42))
    }
}
