package dev.turjo.paperless.core.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.turjo.paperless.core.model.PageFilter
import dev.turjo.paperless.core.model.PageMode
import dev.turjo.paperless.core.ocr.RecognizedText
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ScanTextRepositoryTest {
    @Test
    fun renderCommitsWhileOcrRunsAndSwitchControlsAutomaticRetries() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val prefs = File(context.cacheDir, "ocr-test.preferences_pb")
        val settings = DataStoreSettingsRepository(PreferenceDataStoreFactory.create(scope = scope) { prefs })
        settings.setAutomaticOcr(false)
        try {
            Room.inMemoryDatabaseBuilder(context, PaperlessDatabase::class.java).build().withDatabase { db ->
                val dao = db.documentDao()
                val file = File.createTempFile("processed", ".jpg", context.cacheDir)
                val events = Channel<String>(Channel.UNLIMITED)
                val releaseFirst = CompletableDeferred<Unit>()
                val attempts = AtomicInteger()
                val repository = ScanTextRepository(db, settings, { image, language ->
                    assertEquals(file, image)
                    val attempt = attempts.incrementAndGet()
                    events.send(language)
                    if (attempt == 1) releaseFirst.await()
                    RecognizedText("Result $attempt", emptyList())
                }, scope)
                repository.start()
                dao.insertDocument(DocumentEntity(id = "d", title = "Scan", folderId = null, isFavorite = false,
                    isDraft = false, createdAt = 1, updatedAt = 1, coverPageId = null, deletedAt = null))
                val page = PageEntity(
                    "p", "d", 0, PageMode.NORMAL, file.path, 10, 20, null, 0,
                    PageFilter.DOCUMENT, 0f, 0f, false, false, file.path, "v1", false, 1,
                )
                dao.insertPage(page)
                assertNull(withTimeoutOrNull(200) { events.receive() })
                settings.setAutomaticOcr(true)
                assertEquals("eng+ben", withTimeout(5000) { events.receive() })
                withTimeout(5000) { dao.updatePage(page.copy(processedHash = "v2")) }
                releaseFirst.complete(Unit)
                assertEquals("eng+ben", withTimeout(5000) { events.receive() })
                assertEquals("Result 2", withTimeout(5000) { repository.observe("p").first { it != null } })
                assertEquals("v2", db.scanTextDao().get("p")?.processedHash)
                settings.setAutomaticOcr(false)
                dao.updatePage(page.copy(processedHash = "v3"))
                assertNull(withTimeoutOrNull(200) { events.receive() })
                scope.cancel()
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun onlyWhileChargingWaitsForThePlug() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val prefs = File(context.cacheDir, "ocr-charging-test.preferences_pb")
        val settings = DataStoreSettingsRepository(PreferenceDataStoreFactory.create(scope = scope) { prefs })
        settings.setAutomaticOcr(true)
        settings.setOcrOnlyWhileCharging(true)
        val plugged = MutableStateFlow(false)
        try {
            Room.inMemoryDatabaseBuilder(context, PaperlessDatabase::class.java).build().withDatabase { db ->
                val dao = db.documentDao()
                val file = File.createTempFile("processed", ".jpg", context.cacheDir)
                val events = Channel<String>(Channel.UNLIMITED)
                val repository = ScanTextRepository(db, settings, { _, language ->
                    events.send(language)
                    RecognizedText("Text", emptyList())
                }, scope, object : PowerState {
                    override val charging = plugged
                })
                repository.start()
                dao.insertDocument(DocumentEntity(id = "d", title = "Scan", folderId = null, isFavorite = false,
                    isDraft = false, createdAt = 1, updatedAt = 1, coverPageId = null, deletedAt = null))
                dao.insertPage(PageEntity(
                    "p", "d", 0, PageMode.NORMAL, file.path, 10, 20, null, 0,
                    PageFilter.DOCUMENT, 0f, 0f, false, false, file.path, "v1", false, 1,
                ))
                assertNull(withTimeoutOrNull(300) { events.receive() })
                plugged.value = true
                assertEquals("eng+ben", withTimeout(5000) { events.receive() })
                scope.cancel()
            }
        } finally {
            scope.cancel()
        }
    }
}
