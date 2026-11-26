package dev.turjo.paperless.core.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.turjo.paperless.core.model.PageFilter
import dev.turjo.paperless.core.model.PageMode
import dev.turjo.paperless.core.model.Quad
import dev.turjo.paperless.core.model.paramsHash
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OriginalShrinkJobTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun scalesUprightPixelsAndKeepsFullPageSentinel() {
        val quad = Quad(8f, 12f, 800f, 16f, 788f, 960f, 24f, 944f)
        assertEquals(Quad(4f, 6f, 400f, 8f, 394f, 480f, 12f, 472f), scaledOriginalQuad(quad, 0.5f))
        assertNull(scaledOriginalQuad(null, 0.5f))
        assertNull(scaledOriginalQuad(Quad.FULL, 0.5f))
    }

    @Test
    fun shrinksOnlyEligibleOriginalAndPreservesProcessedData() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val files = FileStore(context)
        val settings = DataStoreSettingsRepository(
            PreferenceDataStoreFactory.create(scope = backgroundScope) { File(temp.root, "settings.preferences_pb") },
        )
        val database = Room.inMemoryDatabaseBuilder(context, PaperlessDatabase::class.java).build()
        try {
            val dao = database.documentDao()
            dao.insertDocument(document("saved"))
            dao.insertDocument(document("draft").copy(isDraft = true))
            dao.insertDocument(document("deleted").copy(deletedAt = 1L))

            val old = page("old").let { row ->
                val processed = temp.newFile("processed.jpg").apply { writeText("processed") }
                val withOutput = row.copy(processedPath = processed.path)
                withOutput.copy(processedHash = withOutput.toModel().paramsHash())
            }
            val original = File(old.originalPath)
            val oldBytes = original.length()
            val untouched = listOf(
                page("new").copy(createdAt = NOW),
                page("draft", "draft"),
                page("deleted", "deleted"),
                page("small", width = 2000),
                page(idCompositeId("source")),
                page("busy"),
            )
            (untouched + old).forEach { dao.insertPage(it) }
            val snapshots = untouched.associateWith { File(it.originalPath).readBytes().toList() }
            database.scanTextDao().put(ScanTextEntity(
                pageId = old.id, processedHash = requireNotNull(old.processedHash),
                processedPath = requireNotNull(old.processedPath), language = "en", text = "receipt", words = "[]",
            ))

            val job = OriginalShrinkJob(database, files, settings) { id, run -> id != "busy" && run() }
            assertEquals(OriginalShrinkResult(0, 0), job.run(NOW))
            settings.setShrinkOriginalsAfterDays(30)
            val result = job.run(NOW)
            assertEquals(1, result.pages)
            assertTrue(result.bytes > 0)
            assertEquals(oldBytes - original.length(), result.bytes)

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(original.path, bounds)
            assertEquals(2400, bounds.outWidth)
            assertEquals(1200, bounds.outHeight)
            assertEquals(ExifInterface.ORIENTATION_ROTATE_90, ExifInterface(original.path)
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED))
            val shrunk = requireNotNull(dao.page(old.id))
            assertEquals(2400, shrunk.originalWidth)
            assertEquals(1200, shrunk.originalHeight)
            assertArrayEquals(
                floatArrayOf(60f, 120f, 1140f, 120f, 1140f, 2280f, 60f, 2280f),
                requireNotNull(shrunk.quad).toArray(),
                0.001f,
            )
            assertEquals(old.processedPath, shrunk.processedPath)
            assertEquals(shrunk.toModel().paramsHash(), shrunk.processedHash)
            assertEquals("processed", File(requireNotNull(shrunk.processedPath)).readText())
            assertEquals("receipt", database.scanTextDao().observe(old.id, "en").first())
            for ((row, bytes) in snapshots) {
                assertEquals(row, dao.page(row.id))
                assertEquals(bytes, File(row.originalPath).readBytes().toList())
            }
            assertEquals(OriginalShrinkResult(0, 0), job.run(NOW))
        } finally {
            database.close()
        }
    }

    private fun document(id: String) = DocumentEntity(id, id, null, false, false, 0, 0, null, null)

    private fun page(id: String, documentId: String = "saved", width: Int = 4000): PageEntity {
        val file = temp.newFile("$id.jpg")
        val bitmap = Bitmap.createBitmap(width, width / 2, Bitmap.Config.ARGB_8888)
        try {
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
        } finally {
            bitmap.recycle()
        }
        ExifInterface(file.path).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }
        return PageEntity(
            id = id, documentId = documentId, index = 0, mode = PageMode.NORMAL,
            originalPath = file.path, originalWidth = width, originalHeight = width / 2,
            quad = Quad(100f, 200f, 1900f, 200f, 1900f, 3800f, 100f, 3800f),
            rotation = 0, filter = PageFilter.DOCUMENT, brightness = 0f, contrast = 0f, shadowFix = false,
            glareFix = false, processedPath = null, processedHash = null, glareWarning = false, createdAt = 0,
        )
    }

    private companion object {
        val NOW = TimeUnit.DAYS.toMillis(100)
    }
}
