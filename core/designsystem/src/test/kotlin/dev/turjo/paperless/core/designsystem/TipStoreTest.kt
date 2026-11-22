package dev.turjo.paperless.core.designsystem

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TipStoreTest {
    private val preferences = object : TipPreferences {
        private val keys = mutableSetOf<String>()

        override fun seen(key: String) = key in keys
        override fun markSeen(key: String) { keys.add(key) }
        override fun clear() = keys.clear()
    }
    private val store = TipStore(preferences)

    @Test
    fun markingOneTipLeavesOtherTipsUnseen() {
        assertFalse(store.seen("reader_lock"))
        store.markSeen("reader_lock")
        store.markSeen("reader_lock")
        assertTrue(TipStore(preferences).seen("reader_lock"))
        assertFalse(store.seen("reader_tap"))
    }

    @Test
    fun resetClearsAllSeenTips() {
        store.markSeen("crop_handles")
        store.markSeen("filter_zoom")
        store.resetAll()
        assertFalse(store.seen("crop_handles"))
        assertFalse(store.seen("filter_zoom"))
        store.markSeen("crop_handles")
        assertTrue(store.seen("crop_handles"))
    }
}
