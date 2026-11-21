package dev.turjo.paperless.feature.camera

import android.graphics.Bitmap
import androidx.lifecycle.viewModelScope
import dev.turjo.paperless.core.data.DocumentRepository
import dev.turjo.paperless.core.data.PageProcessing
import dev.turjo.paperless.core.data.PixelSize
import dev.turjo.paperless.core.data.ScanSessionRepository
import dev.turjo.paperless.core.model.Document
import dev.turjo.paperless.core.model.DocumentSummary
import dev.turjo.paperless.core.model.DocumentWithPages
import dev.turjo.paperless.core.model.Page
import dev.turjo.paperless.core.model.PageFilter
import dev.turjo.paperless.core.model.PageMode
import dev.turjo.paperless.core.model.ScanSession
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class CameraViewModelTest {
    @get:Rule val temporary = TemporaryFolder()
    private val dispatcher = StandardTestDispatcher()
    private val sessions = Sessions()
    private val documents = Documents()
    private lateinit var model: CameraViewModel
    private lateinit var temp: File
    private val pageModes = mutableListOf<PageMode>()
    private val steps = mutableListOf<String>()
    private val processing = object : PageProcessing {
        override val busyPageIds = MutableStateFlow(emptySet<String>())
        override fun enqueueNewPage(pageId: String, filter: PageFilter) =
            enqueueNewPage(pageId, PageMode.NORMAL, filter)

        override fun enqueueNewPage(pageId: String, mode: PageMode, filter: PageFilter) {
            pageModes += mode
            assertEquals(documents.draft.value.pages.last().id, pageId)
            assertEquals(PageFilter.DOCUMENT, filter)
            assertFalse(temp.exists())
            steps += "enqueue"
        }
        override suspend fun awaitIdle(pageIds: Collection<String>) { steps += "idle" }
        override fun enqueueRender(pageId: String): Unit = unused()
        override suspend fun detectQuad(pageId: String) = unused()
        override suspend fun originalBitmap(pageId: String, maxSide: Int): Bitmap = unused()
        override suspend fun previewBitmap(page: Page, maxSide: Int): Bitmap = unused()
        override suspend fun uprightSize(pageId: String): PixelSize? = unused()
    }

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }

    @After fun tearDown() {
        if (::model.isInitialized) model.viewModelScope.cancel()
        Dispatchers.resetMain()
    }

    @Test fun reusesActiveDraft() = runTest {
        sessions.current = session
        documents.addExistingPage()
        createModel()
        runCurrent()

        assertEquals(0, sessions.starts.size)
        assertEquals(listOf("page-0"), model.state.value.pageIds)
    }

    @Test fun startsDraftWithRequestedTargetAndPosition() = runTest {
        createModel()
        runCurrent()

        assertEquals(listOf("target" to 3), sessions.starts)
        assertTrue(model.state.value.ready)
    }

    @Test fun captureAddsPageAndDeletesTempBeforeQueueing() = runTest {
        createModel()
        runCurrent()
        documents.onAdd = { steps += "add" }
        model.capture { destination ->
            assertEquals(temp, destination)
            destination.writeText("capture")
            steps += "capture"
        }
        assertTrue(model.state.value.capturing)
        runCurrent()

        assertEquals(listOf("capture", "add", "enqueue"), steps)
        assertEquals(listOf("page-0"), model.state.value.pageIds)
        assertFalse(model.state.value.capturing)
    }

    @Test fun singleCaptureOpensReview() = runTest {
        createModel(single = true)
        runCurrent()
        model.capture { it.writeText("capture") }
        runCurrent()

        assertEquals(CameraEvent.Captured, model.events.first())
        assertEquals(CameraEvent.Review(session.draftDocumentId), model.events.first())
    }

    @Test fun modeChipsSelectTheModeForCapturesAndImports() = runTest {
        createModel()
        runCurrent()
        assertEquals(ScanMode.DOCUMENT, model.state.value.mode)

        model.setMode(ScanMode.BOOK)
        model.capture { it.writeText("spread") }
        model.setMode(ScanMode.WHITEBOARD)
        assertEquals(ScanMode.BOOK, model.state.value.mode)
        runCurrent()

        model.setMode(ScanMode.WHITEBOARD)
        model.importImages(listOf("board")) { image, file -> file.writeText(image) }
        runCurrent()

        assertEquals(ScanMode.WHITEBOARD, model.state.value.mode)
        assertEquals(listOf(PageMode.BOOK_L, PageMode.WHITEBOARD), pageModes)
        model.viewModelScope.cancel()
        createModel()
        runCurrent()
        assertEquals(ScanMode.WHITEBOARD, model.state.value.mode)
        model.setMode(ScanMode.DOCUMENT)
    }

    @Test fun idCardScansBothSidesInSingleModeBeforeReview() = runTest {
        createModel(single = true)
        runCurrent()
        model.setMode(ScanMode.ID_CARD)
        assertFalse(model.state.value.idBack)

        model.capture { it.writeText("front") }
        runCurrent()
        assertEquals(CameraEvent.Captured, model.events.first())
        assertTrue(model.state.value.idBack)
        assertEquals(listOf("enqueue"), steps)
        model.setMode(ScanMode.BOOK)
        assertEquals(ScanMode.ID_CARD, model.state.value.mode)

        model.importImages(listOf("back", "extra")) { image, file -> file.writeText(image) }
        runCurrent()
        assertFalse(model.state.value.idBack)
        assertEquals(listOf(PageMode.ID_FRONT, PageMode.ID_BACK), pageModes)
        assertEquals(listOf("enqueue", "enqueue", "idle"), steps)
        assertEquals(CameraEvent.Review(session.draftDocumentId), model.events.first())
        model.setMode(ScanMode.DOCUMENT)
    }

    @Test fun failedCaptureDeletesTempAndReenablesShutter() = runTest {
        createModel()
        runCurrent()
        model.capture { throw IOException("Capture failed") }
        runCurrent()

        assertFalse(temp.exists())
        assertTrue(model.state.value.canEdit)
        assertTrue(model.state.value.pageIds.isEmpty())
        assertEquals(CameraEvent.Error, model.events.first())
    }

    @Test fun singleImportKeepsOneImageAndOpensReview() = runTest {
        createModel(single = true)
        runCurrent()
        model.importImages(listOf("first", "second")) { image, file -> file.writeText(image) }
        runCurrent()

        assertEquals(listOf("page-0"), model.state.value.pageIds)
        assertEquals(CameraEvent.Review(session.draftDocumentId), model.events.first())
    }

    @Test fun batchImportContinuesPastUnreadableImage() = runTest {
        createModel()
        runCurrent()
        model.importImages(listOf("first", "unreadable", "last")) { image, file ->
            if (image == "unreadable") throw IOException("Image unavailable")
            file.writeText(image)
        }
        runCurrent()

        assertEquals(listOf("page-0", "page-1"), model.state.value.pageIds)
        assertEquals(listOf("enqueue", "enqueue"), steps)
        assertFalse(model.state.value.importing)
        assertEquals(CameraEvent.Error, model.events.first())
    }

    @Test fun emptyCloseDiscardsWithoutPrompt() = runTest {
        createModel()
        runCurrent()
        model.close()
        runCurrent()

        assertFalse(model.state.value.confirmDiscard)
        assertEquals(listOf(session.id), sessions.discards)
        assertEquals(CameraEvent.Close, model.events.first())
    }

    @Test fun duplexTracksSidesAndReordersDraftBeforeReview() = runTest {
        createModel()
        runCurrent()
        model.toggleDuplex()
        model.scanBacks()
        assertEquals(DuplexPhase.FRONTS, model.state.value.duplexPhase)
        model.importImages(listOf("front1", "front2")) { image, file -> file.writeText(image) }
        model.scanBacks()
        assertEquals(DuplexPhase.FRONTS, model.state.value.duplexPhase)
        runCurrent()
        assertEquals(2, model.state.value.frontCount)

        model.scanBacks()
        assertEquals(DuplexPhase.BACKS, model.state.value.duplexPhase)
        model.capture { it.writeText("back2") }
        runCurrent()
        model.capture { it.writeText("back1") }
        runCurrent()
        assertEquals(2, model.state.value.backCount)

        model.review()
        runCurrent()
        val interleaved = listOf("page-0", "page-3", "page-1", "page-2")
        assertEquals(listOf(interleaved), documents.reorders)
        assertEquals(interleaved, model.state.value.pageIds)
        assertEquals(CameraEvent.Captured, model.events.first())
        assertEquals(CameraEvent.Captured, model.events.first())
        assertEquals(CameraEvent.Review(session.draftDocumentId), model.events.first())
    }

    @Test fun duplexMismatchCanReturnToCameraOrKeepScanOrder() = runTest {
        createModel()
        runCurrent()
        model.toggleDuplex()
        model.importImages(listOf("front1", "front2")) { image, file -> file.writeText(image) }
        runCurrent()
        model.scanBacks()
        model.importImages(listOf("back2")) { image, file -> file.writeText(image) }
        runCurrent()

        model.review()
        runCurrent()
        assertTrue(model.state.value.confirmDuplexMismatch)
        assertFalse(model.state.value.canEdit)
        model.dismissDuplexMismatch()
        assertEquals(DuplexPhase.BACKS, model.state.value.duplexPhase)
        assertTrue(model.state.value.canEdit)

        model.review()
        runCurrent()
        model.keepDuplexOrder()
        assertTrue(documents.reorders.isEmpty())
        assertEquals(listOf("page-0", "page-1", "page-2"), model.state.value.pageIds)
        assertEquals(CameraEvent.Review(session.draftDocumentId), model.events.first())
    }

    @Test fun autoCaptureIsOffInitiallyAndUsesExistingCapturePipeline() = runTest {
        createModel()
        runCurrent()
        val shutter = CaptureController { it.writeText("capture") }
        fun overlay(stable: Long) = OverlayQuad(FloatArray(8), 1f, stable, 10f, FloatArray(256) { 0.2f })
        model.onOverlay(overlay(0), 0, shutter)
        model.onOverlay(overlay(700), 700, shutter)
        assertFalse(model.state.value.capturing)

        model.toggleAutoCapture()
        model.onOverlay(overlay(800), 800, shutter)
        model.onOverlay(overlay(1500), 1500, shutter)
        assertTrue(model.state.value.capturing)
        runCurrent()
        model.onOverlay(overlay(2300), 2300, shutter)
        assertFalse(model.state.value.capturing)
        assertEquals(listOf("page-0"), model.state.value.pageIds)
    }

    @Test fun keepingPagesOpensReviewWithoutDiscarding() = runTest {
        documents.addExistingPage()
        createModel()
        runCurrent()
        model.close()
        runCurrent()
        assertTrue(model.state.value.confirmDiscard)
        model.keep()

        assertFalse(model.state.value.confirmDiscard)
        assertTrue(sessions.discards.isEmpty())
        assertEquals(CameraEvent.Review(session.draftDocumentId), model.events.first())
    }

    @Test fun confirmingDiscardWaitsForProcessingAndCloses() = runTest {
        documents.addExistingPage()
        createModel()
        runCurrent()
        model.close()
        runCurrent()
        assertTrue(model.state.value.confirmDiscard)
        sessions.onDiscard = { steps += "discard" }
        model.discard()
        runCurrent()

        assertEquals(listOf("idle", "discard"), steps)
        assertEquals(listOf(session.id), sessions.discards)
        assertEquals(CameraEvent.Close, model.events.first())
    }

    private fun createModel(single: Boolean = false) {
        model = CameraViewModel(
            CameraArgs("target", 3, single), sessions, documents, processing,
            { temporary.newFile().also { temp = it } },
            { PixelSize(400, 300) }, { error("No thumbnail needed") }, dispatcher,
        )
    }

    private class Sessions : ScanSessionRepository {
        private val active = MutableStateFlow<ScanSession?>(null)
        var current: ScanSession?
            get() = active.value
            set(value) { active.value = value }
        val starts = mutableListOf<Pair<String?, Int?>>()
        val discards = mutableListOf<String>()
        var onDiscard = {}

        override suspend fun active() = current
        override fun observeActive() = active
        override suspend fun start(targetDocumentId: String?, insertAt: Int?): ScanSession {
            starts += targetDocumentId to insertAt
            return session.also { current = it }
        }
        override suspend fun finish(sessionId: String): Unit = unused()
        override suspend fun discard(sessionId: String) {
            onDiscard()
            discards += sessionId
            current = null
        }
    }

    private class Documents : DocumentRepository {
        val draft = MutableStateFlow(
            DocumentWithPages(Document("draft", "", null, false, true, 0, 0, null, null), emptyList()),
        )
        var onAdd = {}
        val reorders = mutableListOf<List<String>>()

        override fun observeDocument(id: String) = draft
        override suspend fun addPage(documentId: String, originalFile: File, width: Int, height: Int, at: Int?): Page {
            assertEquals("draft", documentId)
            assertEquals(400, width)
            assertEquals(300, height)
            assertTrue(originalFile.length() > 0)
            onAdd()
            return addExistingPage()
        }

        fun addExistingPage(): Page {
            val index = draft.value.pages.size
            val page = Page(
                "page-$index", "draft", index, PageMode.NORMAL, "", 400, 300, null, 0,
                PageFilter.ORIGINAL, 0f, 0f, false, false, null, null, false, 0,
            )
            draft.value = draft.value.copy(pages = draft.value.pages + page)
            return page
        }

        override fun observeLibrary(): MutableStateFlow<List<DocumentSummary>> = unused()
        override suspend fun getPage(id: String): Page? = unused()
        override suspend fun updatePage(page: Page): Unit = unused()
        override suspend fun reorderPages(documentId: String, orderedPageIds: List<String>) {
            assertEquals("draft", documentId)
            reorders.add(orderedPageIds)
            val pages = draft.value.pages.associateBy { it.id }
            draft.value = draft.value.copy(pages = orderedPageIds.mapIndexed { index, id ->
                pages.getValue(id).copy(index = index)
            })
        }
        override suspend fun deletePage(pageId: String): Unit = unused()
        override suspend fun rotatePage(pageId: String, delta: Int): Unit = unused()
        override suspend fun rename(id: String, title: String): Unit = unused()
        override suspend fun setFavorite(id: String, isFavorite: Boolean): Unit = unused()
        override suspend fun softDelete(id: String): Unit = unused()
        override suspend fun restore(id: String): Unit = unused()
        override suspend fun commitDraft(draftId: String, title: String, targetDocumentId: String?, insertAt: Int?): String = unused()
    }

    private companion object {
        val session = ScanSession("session", "draft", 0, "target", 3)
        fun unused(): Nothing = error("Unexpected call")
    }
}
