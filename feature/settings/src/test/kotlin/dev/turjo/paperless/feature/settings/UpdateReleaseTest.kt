package dev.turjo.paperless.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateReleaseTest {
    @Test
    fun websiteRedirectGivesTheVersion() {
        assertEquals("1.1.0", tagFromReleaseLocation("https://github.com/NawrizTurjo/Scanly/releases/tag/v1.1.0"))
        assertEquals("2.0.10", tagFromReleaseLocation("/NawrizTurjo/Scanly/releases/tag/v2.0.10?x=1"))
        assertNull(tagFromReleaseLocation("https://github.com/NawrizTurjo/Scanly/releases"))
        assertNull(tagFromReleaseLocation("https://github.com/NawrizTurjo/Scanly/releases/tag/latest"))
        assertNull(tagFromReleaseLocation("https://github.com/NawrizTurjo/Scanly/releases/tag/v1.1"))
    }

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
    fun ordersNightlies() {
        assertTrue(isNewerVersion("2.0.0-nightly.1", "1.1.4"))
        assertTrue(isNewerVersion("2.0.0-nightly.2", "2.0.0-nightly.1"))
        assertTrue(isNewerVersion("2.0.0-nightly.10", "2.0.0-nightly.9"))
        assertTrue(isNewerVersion("2.0.0", "2.0.0-nightly.3"))
        assertFalse(isNewerVersion("2.0.0-nightly.3", "2.0.0"))
        assertFalse(isNewerVersion("2.0.0-nightly.1", "2.0.0-nightly.1"))
        assertFalse(isNewerVersion("1.1.4", "2.0.0-nightly.1"))
        assertEquals("2.0.0-nightly.2", tagFromReleaseLocation("https://github.com/o/r/releases/tag/v2.0.0-nightly.2"))
    }

    @Test
    fun atomFeedGivesTheNewestTagIncludingNightlies() {
        val feed = listOf("v1.1.4", "v2.0.0-nightly.2", "v2.0.0-nightly.10", "vbroken").joinToString("") {
            "<entry><link rel=\"alternate\" href=\"https://github.com/o/r/releases/tag/$it\"/></entry>"
        }
        assertEquals("2.0.0-nightly.10", newestTagFromAtom("<feed>$feed</feed>"))
        assertNull(newestTagFromAtom("<feed/>"))
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
