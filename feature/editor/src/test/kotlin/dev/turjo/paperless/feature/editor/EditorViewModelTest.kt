package dev.turjo.paperless.feature.editor

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.turjo.paperless.core.data.DocumentRepository
import dev.turjo.paperless.core.data.PageProcessing
import dev.turjo.paperless.core.data.ScanSessionRepository
import dev.turjo.paperless.core.model.Document
import dev.turjo.paperless.core.model.DocumentSummary
import dev.turjo.paperless.core.model.DocumentWithPages
import dev.turjo.paperless.core.model.Page
import dev.turjo.paperless.core.model.PageFilter
import dev.turjo.paperless.core.model.PageMode
import dev.turjo.paperless.core.model.ScanSession
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
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
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EditorViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val calls = mutableListOf<String>()
    private val documents = FakeDocuments(calls)
    private val sessions = FakeSessions(calls)
    private val processing = FakeProcessing()
    private val viewModels = mutableListOf<ViewModel>()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        viewModels.forEach { it.viewModelScope.cancel() }
        Dispatchers.resetMain()
    }

    @Test
    fun reorderPersistsAndUpdatesReviewOrder() = runTest(dispatcher) {
        val vm = review()
        runCurrent()

        vm.reorder(listOf("second", "first"))
        runCurrent()

        assertEquals(listOf("second", "first"), documents.draft.pages.map { it.id })
        assertEquals(listOf(0, 1), documents.draft.pages.map { it.index })
        assertEquals(listOf("second", "first"), vm.state.value.pages.map { it.page.id })
    }

    @Test
    fun filterAllUpdatesEachPageAndEnqueuesItsRender() = runTest(dispatcher) {
        val vm = review()
        runCurrent()

        vm.filterAll(PageFilter.WHITEBOARD)
        runCurrent()

        assertEquals(listOf(PageFilter.WHITEBOARD, PageFilter.WHITEBOARD), documents.draft.pages.map { it.filter })
        assertEquals(listOf("first", "second"), documents.updates.map { it.id })
        assertEquals(listOf("first", "second"), processing.renderedIds)
    }

    @Test
    fun saveCommitsDraftAndFinishesSessionWithoutWaitingForRenders() = runTest(dispatcher) {
        val vm = review()
        processing.busyPageIds.value = setOf("first")
        runCurrent()

        vm.save("New scan")
        runCurrent()

        assertEquals(listOf(Commit("draft", "New scan", null, null)), documents.commits)
        assertEquals(listOf("commit", "finish:session"), calls)
        assertTrue(processing.waits.isEmpty())
        assertFalse(documents.draft.document.isDraft)
        assertEquals("draft", vm.state.value.savedId)
    }

    @Test
    fun appendUsesSessionTargetAndInsertionIndex() = runTest(dispatcher) {
        sessions.current.value = sessions.current.value?.copy(targetDocumentId = "target", insertAt = 1)
        val vm = review()
        runCurrent()

        assertEquals("Existing scan", vm.state.value.targetTitle)
        vm.save("")
        runCurrent()

        assertEquals(listOf(Commit("draft", "", "target", 1)), documents.commits)
        assertEquals(listOf("commit", "finish:session"), calls)
        assertEquals("target", vm.state.value.savedId)
        assertEquals(listOf("existing", "first", "second"), documents.target.pages.map { it.id })
    }

    @Test
    fun undoRedoGroupsSliderGestureAndKeepsEditsInMemory() = runTest(dispatcher) {
        val vm = editor()
        runCurrent()

        vm.edit { it.copy(rotation = 90) }
        vm.beginGesture()
        vm.edit { it.copy(brightness = 0.25f) }
        vm.edit { it.copy(brightness = 0.5f) }
        vm.endGesture()
        vm.undo()

        assertEquals(90, vm.state.value.edits?.rotation)
        assertEquals(0f, vm.state.value.edits?.brightness)
        assertTrue(vm.state.value.canRedo)
        vm.undo()
        assertFalse(vm.state.value.dirty)

        vm.redo()
        vm.redo()
        assertEquals(90, vm.state.value.edits?.rotation)
        assertEquals(0.5f, vm.state.value.edits?.brightness)
        assertTrue(vm.state.value.dirty)
        assertFalse(vm.state.value.canRedo)
        assertTrue(documents.updates.isEmpty())
    }

    @Test
    fun doneWritesOnceAndPreservesLatestProcessingMetadata() = runTest(dispatcher) {
        val vm = editor()
        runCurrent()
        val latest = documents.draft.pages.first().copy(processedHash = "current-hash", glareWarning = true)
        documents.replacePage(latest)
        vm.edit { it.copy(filter = PageFilter.BW, rotation = 270) }
        vm.done()
        vm.done()
        runCurrent()
        vm.done()
        runCurrent()

        assertEquals(listOf(latest.copy(filter = PageFilter.BW, rotation = 270)), documents.updates)
        assertEquals(listOf("first"), processing.renderedIds)
        assertTrue(vm.state.value.saved)
        assertFalse(vm.state.value.dirty)
    }

    private fun review() = ReviewViewModel(
        "draft", documents, sessions, processing, { id -> File("$id.webp") }, dispatcher,
    ).also(viewModels::add)

    private fun editor() = PageEditorViewModel("first", documents, processing).also(viewModels::add)

    private data class Commit(val draftId: String, val title: String, val targetId: String?, val insertAt: Int?)

    private class FakeDocuments(private val calls: MutableList<String>) : DocumentRepository {
        private val draftFlow = MutableStateFlow(
            DocumentWithPages(document("draft", "Draft", true), listOf(page("first", 0), page("second", 1))),
        )
        private val targetFlow = MutableStateFlow(
            DocumentWithPages(document("target", "Existing scan", false), listOf(page("existing", 0, "target"))),
        )
        val draft: DocumentWithPages get() = draftFlow.value
        val target: DocumentWithPages get() = targetFlow.value
        val updates = mutableListOf<Page>()
        val commits = mutableListOf<Commit>()

        override fun observeDocument(id: String) = when (id) {
            "draft" -> draftFlow
            "target" -> targetFlow
            else -> error("Unknown document $id")
        }

        override suspend fun getPage(id: String) = (draft.pages + target.pages).firstOrNull { it.id == id }

        fun replacePage(page: Page) {
            draftFlow.value = draft.copy(pages = draft.pages.map { if (it.id == page.id) page else it })
        }

        override suspend fun updatePage(page: Page) {
            updates += page
            replacePage(page)
        }

        override suspend fun reorderPages(documentId: String, orderedPageIds: List<String>) {
            require(documentId == draft.document.id)
            draftFlow.value = draft.copy(
                pages = orderedPageIds.mapIndexed { index, id -> draft.pages.first { it.id == id }.copy(index = index) },
            )
        }

        override suspend fun commitDraft(draftId: String, title: String, targetDocumentId: String?, insertAt: Int?): String {
            calls += "commit"
            commits += Commit(draftId, title, targetDocumentId, insertAt)
            if (targetDocumentId == null) {
                draftFlow.value = draft.copy(document = draft.document.copy(title = title, isDraft = false))
                return draftId
            }
            val merged = target.pages.toMutableList()
            merged.addAll(insertAt ?: merged.size, draft.pages)
            targetFlow.value = target.copy(
                pages = merged.mapIndexed { index, page -> page.copy(index = index, documentId = targetDocumentId) },
            )
            return targetDocumentId
        }

        override fun observeLibrary() = draftFlow.map { emptyList<DocumentSummary>() }
        override suspend fun addPage(documentId: String, originalFile: File, width: Int, height: Int, at: Int?): Nothing =
            unexpected()
        override suspend fun deletePage(pageId: String): Nothing = unexpected()
        override suspend fun rotatePage(pageId: String, delta: Int): Nothing = unexpected()
        override suspend fun rename(id: String, title: String): Nothing = unexpected()
        override suspend fun setFavorite(id: String, isFavorite: Boolean): Nothing = unexpected()
        override suspend fun softDelete(id: String): Nothing = unexpected()
        override suspend fun restore(id: String): Nothing = unexpected()
    }

    private class FakeSessions(private val calls: MutableList<String>) : ScanSessionRepository {
        val current = MutableStateFlow<ScanSession?>(ScanSession("session", "draft", 0L, null, null))

        override fun observeActive() = current
        override suspend fun active() = current.value

        override suspend fun finish(sessionId: String) {
            calls += "finish:$sessionId"
            if (current.value?.id == sessionId) current.value = null
        }

        override suspend fun start(targetDocumentId: String?, insertAt: Int?): Nothing = unexpected()
        override suspend fun discard(sessionId: String): Nothing = unexpected()
    }

    private class FakeProcessing : PageProcessing {
        override val busyPageIds = MutableStateFlow<Set<String>>(emptySet())
        val renderedIds = mutableListOf<String>()
        val waits = mutableListOf<List<String>>()

        override fun enqueueRender(pageId: String) {
            renderedIds += pageId
        }

        override suspend fun awaitIdle(pageIds: Collection<String>) {
            waits += pageIds.toList()
        }

        override suspend fun uprightSize(pageId: String) = null
        override fun enqueueNewPage(pageId: String, filter: PageFilter): Nothing = unexpected()
        override suspend fun detectQuad(pageId: String): Nothing = unexpected()
        override suspend fun originalBitmap(pageId: String, maxSide: Int): Bitmap = unexpected()
        override suspend fun previewBitmap(page: Page, maxSide: Int): Bitmap = unexpected()
    }

    private companion object {
        fun document(id: String, title: String, draft: Boolean) = Document(
            id, title, null, false, draft, 0L, 0L, null, null,
        )

        fun page(id: String, index: Int, documentId: String = "draft") = Page(
            id, documentId, index, PageMode.NORMAL, "$id.jpg", 1200, 800,
            null, 0, PageFilter.ORIGINAL, 0f, 0f, false, false, null, null, false, 0L,
        )

        fun unexpected(): Nothing = error("Unexpected fake dependency call")
    }
}
