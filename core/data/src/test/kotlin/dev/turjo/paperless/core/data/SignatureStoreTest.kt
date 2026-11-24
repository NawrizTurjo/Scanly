package dev.turjo.paperless.core.data

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SignatureStoreTest {
    @Test
    fun deletingASavedSignatureKeepsOnlyInkUsedForPlacement() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = SignatureStore(context)
        val bitmap = Bitmap.createBitmap(8, 4, Bitmap.Config.ARGB_8888)
        try {
            bitmap.setPixel(2, 1, Color.BLUE)
            val used = store.save(bitmap)
            val unused = store.save(bitmap)
            assertEquals(listOf(used, unused), SignatureStore(context).list())
            assertEquals(2f, used.aspect, 0f)
            store.retainForPlacement(used.id)
            store.delete(used.id)
            store.delete(unused.id)
            assertTrue(store.list().isEmpty())
            assertFalse(store.placedFile(unused.id).exists())
            assertNull(store.bitmap(used.id))
            assertNull(store.bitmap(unused.id))
            val retained = checkNotNull(store.bitmap(used.id, placed = true))
            try {
                assertEquals(Color.BLUE, retained.getPixel(2, 1))
                assertEquals(Color.TRANSPARENT, retained.getPixel(0, 0))
            } finally {
                retained.recycle()
            }
        } finally {
            bitmap.recycle()
        }
    }
}
