package dev.turjo.paperless

import dev.turjo.paperless.core.data.FreshScanSession
import dev.turjo.paperless.core.data.PixelSize
import dev.turjo.paperless.core.data.ScanSessionRepository
import dev.turjo.paperless.core.model.ScanSession
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SharedImageImportTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun savesPreviousScanBeforeAddingImagesToOneFreshDraft() = runBlocking {
        val sessions = Sessions()
        val result = importImageBatch(
            images = listOf("unreadable", "first", "second"),
            sessions = sessions,
            newTempFile = { temporary.newFile() },
            readImage = { image, temp ->
                temp.writeText(image)
                if (image == "unreadable") PixelSize(-1, -1) else PixelSize(100, 200)
            },
            addPage = { draftId, temp, size ->
                assertEquals(PixelSize(100, 200), size)
                sessions.operations += "$draftId: ${temp.readText()}"
            },
        )

        assertEquals(
            listOf("saved previous-draft", "fresh-draft: first", "fresh-draft: second"),
            sessions.operations,
        )
        assertEquals(SharedImageResult("fresh-draft", hadErrors = true, previousScanSaved = true), result)
        assertTrue(temporary.root.listFiles().orEmpty().isEmpty())
    }

    @Test fun leavesPreviousScanOpenWhenNoImageIsUsable() = runBlocking {
        val sessions = Sessions()
        val result = importImageBatch(
            images = listOf("unreadable"),
            sessions = sessions,
            newTempFile = { temporary.newFile() },
            readImage = { _, _ -> PixelSize(-1, -1) },
            addPage = { _, _, _ -> error("An unreadable image cannot be added") },
        )

        assertEquals(SharedImageResult(null, hadErrors = true, previousScanSaved = false), result)
        assertEquals(emptyList<String>(), sessions.operations)
        assertEquals("previous-draft", sessions.active()?.draftDocumentId)
    }

    private class Sessions : ScanSessionRepository {
        private var current = ScanSession("previous", "previous-draft", 0, null, null)
        val operations = mutableListOf<String>()

        override suspend fun startFresh(
            saveExisting: Boolean,
            targetDocumentId: String?,
            insertAt: Int?,
            expectedSessionId: String?,
        ): FreshScanSession {
            val savedTitle = if (saveExisting) {
                operations += "saved ${current.draftDocumentId}"
                "Scan"
            } else null
            current = ScanSession("fresh", "fresh-draft", 1, targetDocumentId, insertAt)
            return FreshScanSession(session = current, savedTitle = savedTitle)
        }

        override fun observeActive() = flowOf(current)
        override suspend fun active() = current
        override suspend fun start(targetDocumentId: String?, insertAt: Int?) = unexpected()
        override suspend fun finish(sessionId: String) = unexpected()
        override suspend fun discard(sessionId: String) = unexpected()

        private fun unexpected(): Nothing = error("Expected the importer to use startFresh")
    }
}
