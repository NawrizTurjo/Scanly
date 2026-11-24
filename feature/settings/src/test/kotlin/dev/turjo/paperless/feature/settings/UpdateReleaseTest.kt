package dev.turjo.paperless.feature.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateReleaseTest {
    @Test
    fun comparesNumericVersions() {
        assertTrue(isNewerVersion("v1.10.0", "1.9.9"))
        assertTrue(isNewerVersion("2.0.0", "1.99.99"))
        assertTrue(isNewerVersion("1.2.3", "1.2.3-beta"))
        assertFalse(isNewerVersion("1.2.3", "1.2.3+debug"))
        assertFalse(isNewerVersion("1.2.3", "1.3.0"))
        assertFalse(isNewerVersion("invalid", "1.2.3"))
    }

    @Test
    fun automaticCheckRequiresTwentyFourHours() {
        val day = 86_400_000L
        assertTrue(shouldAutoCheck(true, null, day))
        assertTrue(shouldAutoCheck(true, 1, day + 1))
        assertFalse(shouldAutoCheck(true, 1, day))
        assertFalse(shouldAutoCheck(false, null, day))
    }

    @Test
    fun skipAppliesOnlyToSelectedVersion() {
        assertFalse(shouldShowRelease("1.2.3", "1.2.3"))
        assertTrue(shouldShowRelease("1.2.4", "1.2.3"))
        assertTrue(shouldShowRelease("1.2.3", null))
    }
}
