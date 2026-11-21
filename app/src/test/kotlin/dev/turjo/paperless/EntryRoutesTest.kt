package dev.turjo.paperless

import dev.turjo.paperless.core.model.AppLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EntryRoutesTest {
    @Test
    fun shortcutsBuildLibraryAndDestinationStack() {
        assertEquals(listOf(LibraryRoute, CameraRoute()), startBackStack("camera_batch"))
        assertEquals(listOf(LibraryRoute, CameraRoute(single = true)), startBackStack("camera_single"))
        assertEquals(listOf(LibraryRoute, DocumentRoute("abc")), startBackStack("document:abc"))
    }

    @Test
    fun missingAndInvalidRoutesOpenLibrary() {
        for (value in listOf(null, "camera", "document:", "document: ")) {
            assertEquals(listOf(LibraryRoute), startBackStack(value))
        }
    }

    @Test
    fun coldStartLocksOnlyWhenEnabled() {
        assertFalse(shouldLock(AppLock.OFF, 5, null, 0L, true))
        assertTrue(shouldLock(AppLock.ON_OPEN, 5, null, 0L, true))
        assertTrue(shouldLock(AppLock.AFTER_MINUTES, 5, null, 0L, true))
    }

    @Test
    fun onOpenLocksOnReturnFromBackground() {
        assertFalse(shouldLock(AppLock.ON_OPEN, 5, null, 1L, false))
        assertTrue(shouldLock(AppLock.ON_OPEN, 5, 0L, 1L, false))
    }

    @Test
    fun timeoutLocksAtTheConfiguredThreshold() {
        assertFalse(shouldLock(AppLock.AFTER_MINUTES, 5, 1_000L, 300_999L, false))
        assertTrue(shouldLock(AppLock.AFTER_MINUTES, 5, 1_000L, 301_000L, false))
    }
}
