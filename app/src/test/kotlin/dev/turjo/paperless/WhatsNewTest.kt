package dev.turjo.paperless

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WhatsNewTest {
    @Test fun freshInstallDoesNotPrompt() {
        assertFalse(WhatsNew.shouldShow(null, "1.1.2", previouslyUpdated = false))
    }

    @Test fun sameVersionDoesNotPrompt() {
        assertFalse(WhatsNew.shouldShow("1.1.2", "1.1.2", previouslyUpdated = true))
    }

    @Test fun upgradePrompts() {
        assertTrue(WhatsNew.shouldShow("1.1.1", "1.1.2", previouslyUpdated = true))
    }

    @Test fun upgradeFromBeforeTheTourWasAddedPrompts() {
        assertTrue(WhatsNew.shouldShow(null, "1.1.2", previouslyUpdated = true))
    }

    @Test fun downgradePromptsWhenTheVersionChanges() {
        assertTrue(WhatsNew.shouldShow("1.2.0", "1.1.2", previouslyUpdated = true))
    }

    @Test fun releaseLookupUsesTheExactVersion() {
        val release = checkNotNull(WhatsNew.releaseFor("1.1.2"))
        assertEquals("1.1.2", release.version)
        assertEquals(10, release.cards.size)
        assertNull(WhatsNew.releaseFor("1.1.1"))
        assertNull(WhatsNew.releaseFor("1.2.0"))
    }
}
