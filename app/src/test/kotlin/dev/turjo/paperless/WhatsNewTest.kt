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

    @Test fun nightliesShowTheTourOnceForTheirVersion() {
        assertTrue(WhatsNew.shouldShow("1.1.4", "2.0.0-nightly.1", previouslyUpdated = true))
        assertFalse(WhatsNew.shouldShow("2.0.0-nightly.1", "2.0.0-nightly.2", previouslyUpdated = true))
        assertFalse(WhatsNew.shouldShow("2.0.0-nightly.2", "2.0.0", previouslyUpdated = true))
        assertEquals(WhatsNew.releaseFor("1.1.4"), WhatsNew.releaseFor("1.1.4-nightly.3"))
    }

    @Test fun releaseLookupUsesTheExactVersion() {
        val release = checkNotNull(WhatsNew.releaseFor("1.1.2"))
        assertEquals("1.1.2", release.version)
        assertEquals(10, release.cards.size)
        assertNull(WhatsNew.releaseFor("1.1.1"))
        assertNull(WhatsNew.releaseFor("1.2.0"))
    }

    @Test fun tourCoversEveryReleaseSinceTheLastOne() {
        val tour = checkNotNull(WhatsNew.tourFor("1.0.0", "1.1.3"))
        assertEquals("1.1.3", tour.version)
        assertEquals(13, tour.cards.size)
        assertEquals(R.string.whats_new_zoom_title, tour.cards.first().title)
    }

    @Test fun tourFromTwoVersionsBackJoinsBothReleases() {
        val tour = checkNotNull(WhatsNew.tourFor("1.1.2", "1.1.4"))
        assertEquals(6, tour.cards.size)
        assertEquals(R.string.whats_new_sharp_title, tour.cards.first().title)
    }

    @Test fun tourAfterOneStepShowsOnlyTheNewRelease() {
        assertEquals(checkNotNull(WhatsNew.releaseFor("1.1.3")).cards, WhatsNew.tourFor("1.1.2", "1.1.3")?.cards)
    }

    @Test fun nightlyShowsTheCardsOfTheReleaseItLeadsTo() {
        val tour = checkNotNull(WhatsNew.tourFor("1.1.4", "2.0.0-nightly.1"))
        assertEquals(8, tour.cards.size)
        assertEquals(R.string.whats_new_glass_title, tour.cards.first().title)
    }

    @Test fun tourWithoutAPreviousVersionShowsEverything() {
        assertEquals(13, WhatsNew.tourFor(null, "1.1.3")?.cards?.size)
    }

    @Test fun tourSkipsReleasesNewerThanTheApp() {
        assertEquals(10, WhatsNew.tourFor("1.0.0", "1.1.2")?.cards?.size)
    }

    @Test fun tourFallsBackToTheCurrentReleaseOnDowngrade() {
        assertEquals(10, WhatsNew.tourFor("1.1.3", "1.1.2")?.cards?.size)
        assertNull(WhatsNew.tourFor("1.2.0", "1.0.5"))
    }
}
