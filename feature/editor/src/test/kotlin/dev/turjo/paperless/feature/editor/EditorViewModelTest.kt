package dev.turjo.paperless.feature.editor

import android.graphics.Bitmap
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.turjo.paperless.core.data.DocumentRepository
import dev.turjo.paperless.core.data.IdComposite
import dev.turjo.paperless.core.imaging.CardPlacement
import dev.turjo.paperless.core.imaging.IdCardLayout
import dev.turjo.paperless.core.data.PageProcessing
import dev.turjo.paperless.core.data.ScanSessionRepository
import dev.turjo.paperless.core.model.Document
import dev.turjo.paperless.core.model.DocumentSummary
import dev.turjo.paperless.core.model.DocumentWithPages
import dev.turjo.paperless.core.model.InkRemoval
import dev.turjo.paperless.core.model.Page
import dev.turjo.paperless.core.model.PageBlankInfo
import dev.turjo.paperless.core.model.BatchCrop
import dev.turjo.paperless.core.model.PageBatchEdits
import dev.turjo.paperless.core.model.PageFilter
import dev.turjo.paperless.core.model.Quad
import dev.turjo.paperless.core.model.PageMode
import dev.turjo.paperless.core.model.ScanSession
import dev.turjo.paperless.core.model.splitGroups
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
    private val saved = mutableListOf<String>()
    private val sink = object : PageImageSink {
        override suspend fun shareCopy(rendered: File, name: String) = File(name)
        override suspend fun saveToPictures(rendered: File, name: String) {
            saved += name
        }
    }
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
    fun batchEditsPreserveUntouchedFieldsAndRenderOnlyChangedPages() = runTest(dispatcher) {
        val first = documents.draft.pages.first().copy(quad = Quad.FULL, glareFix = true, rotation = 90)
        val second = documents.draft.pages.last().copy(filter = PageFilter.WHITEBOARD, deskew = false)
        documents.replacePage(first)
        documents.replacePage(second)
        val vm = review()
        runCurrent()

        vm.applyToAllPages(PageBatchEdits(filter = PageFilter.WHITEBOARD, deskew = false))
        runCurrent()

        val edited = first.copy(filter = PageFilter.WHITEBOARD, deskew = false)
        assertEquals(listOf(edited, second), documents.draft.pages)
        assertEquals(listOf(edited), documents.updates)
        assertEquals(listOf("first"), processing.renderedIds)
        assertEquals(listOf("first"), processing.waits.last())
    }

    @Test
    fun batchFullPhotoClearsOnlyActualCrops() = runTest(dispatcher) {
        val first = documents.draft.pages.first().copy(quad = Quad(1f, 1f, 10f, 1f, 10f, 10f, 1f, 10f))
        val second = documents.draft.pages.last().copy(quad = Quad.FULL)
        documents.replacePage(first)
        documents.replacePage(second)
        val vm = review()
        runCurrent()

        vm.applyToAllPages(PageBatchEdits(crop = BatchCrop.FULL_PHOTO))
        runCurrent()

        assertEquals(listOf(first.copy(quad = null), second), documents.draft.pages)
        assertEquals(listOf("first"), processing.renderedIds)
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
    fun idLayoutChangeRecomposesAndRefreshesThePreviewPage() = runTest(dispatcher) {
        val id = "id-composite-second"
        documents.addFakePage(page(id, 2))
        val vm = PageEditorViewModel(id, documents, processing).also(viewModels::add)
        runCurrent()
        assertTrue(vm.state.value.canChangeIdLayout)

        processing.onLayout = { documents.replacePage(page(id, 2).copy(originalPath = "recomposed.jpg")) }
        vm.changeIdLayout { it.copy(gapMm = 15, outline = true) }
        runCurrent()

        val layout = IdCardLayout(gapMm = 15, outline = true)
        assertEquals(listOf(id to layout), processing.layouts)
        assertEquals(layout, vm.state.value.idLayout)
        assertEquals("recomposed.jpg", vm.state.value.page?.originalPath)
        assertTrue(documents.updates.isEmpty())
    }

    @Test
    fun finishingPlacementSavesAndChoosingTheSamePresetClearsIt() = runTest(dispatcher) {
        val id = "id-composite-second"
        documents.addFakePage(page(id, 2))
        val vm = PageEditorViewModel(id, documents, processing).also(viewModels::add)
        runCurrent()
        val preset = requireNotNull(vm.state.value.idLayout)
        val free = preset.copy(
            front = CardPlacement(0.2f, 0.2f, 0.4f),
            back = CardPlacement(0.3f, 0.6f, 0.5f),
        )

        vm.finishIdPlacement(free)
        assertEquals(free, vm.state.value.idLayout)
        assertTrue(vm.state.value.saving)
        runCurrent()
        vm.changeIdPreset { it.copy(arrangement = preset.arrangement) }
        runCurrent()

        assertEquals(listOf(id to free, id to preset), processing.layouts)
        assertEquals(preset, vm.state.value.idLayout)
        assertFalse(vm.state.value.saving)
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

    @Test
    fun duplexSplitMatchesModelAndRestoresBlankBackInScanOrder() = runTest(dispatcher) {
        val ids = listOf("front", "blank-back", "next-front", "next-back", "sep-front", "sep-back", "last-front")
        documents.setDraftPages(ids)
        processing.blanks = setOf("blank-back", "sep-front", "sep-back")
        val vm = review()
        runCurrent()

        vm.splitAtBlanks(duplex = true)
        runCurrent()

        val expected = splitGroups(ids.map { PageBlankInfo(it, it in processing.blanks) }, duplex = true)
        assertEquals(expected.map { it.pageIds }, vm.state.value.groups.map { it.kept })
        assertEquals(expected.map { it.droppedSeparatorIds }, vm.state.value.groups.map { it.dropped })
        vm.restoreSeparator("blank-back")
        assertEquals(listOf("front", "blank-back", "next-front", "next-back"), vm.state.value.groups.first().kept)
        assertEquals(listOf("sep-front", "sep-back"), vm.state.value.groups.first().dropped)
    }

    @Test
    fun manualSplitRenameAndJoinRetainPageOrderAcrossStateRestoration() = runTest(dispatcher) {
        val savedState = SavedStateHandle()
        val vm = review(savedState)
        runCurrent()

        vm.splitHere("second")
        vm.renameGroup(1, "Second document")
        val restored = review(savedState)
        runCurrent()

        assertEquals(
            listOf(ReviewGroup("Draft (1)", listOf("first")), ReviewGroup("Second document", listOf("second"))),
            restored.state.value.groups,
        )
        restored.join(1)
        assertEquals(listOf(ReviewGroup("Draft (1)", listOf("first", "second"))), restored.state.value.groups)
    }

    @Test
    fun groupedSaveMovesPagesInOrderAndDeletesOnlyUnrestoredSeparators() = runTest(dispatcher) {
        documents.setDraftPages(listOf("a", "b", "sep1", "c", "d", "sep2", "e"))
        processing.blanks = setOf("sep1", "sep2")
        val vm = review()
        runCurrent()
        vm.splitAtBlanks(duplex = false)
        runCurrent()
        vm.restoreSeparator("sep1")
        vm.renameGroup(1, "Middle pages")
        vm.reorder(listOf("b", "a", "sep1", "d", "c", "e"))
        runCurrent()

        vm.save("")
        runCurrent()

        val saved = listOf(documents.draft) + documents.splitDocuments
        assertEquals(listOf("Draft (1)", "Middle pages", "Draft (3)"), saved.map { it.document.title })
        assertTrue(saved.none { it.document.isDraft })
        assertEquals(listOf(listOf("b", "a", "sep1"), listOf("d", "c"), listOf("e")), saved.map { doc ->
            doc.pages.map { it.id }
        })
        assertEquals(listOf("sep2"), documents.deletedIds)
        assertEquals("draft", vm.state.value.savedId)
    }

    @Test
    fun groupedAppendAddsOnlyFirstGroupToSessionTarget() = runTest(dispatcher) {
        sessions.current.value = sessions.current.value?.copy(targetDocumentId = "target", insertAt = 0)
        val vm = review()
        runCurrent()
        vm.splitHere("second")

        vm.save("")
        runCurrent()

        assertEquals(listOf("first", "existing"), documents.target.pages.map { it.id })
        assertEquals(listOf("second"), documents.splitDocuments.single().pages.map { it.id })
        assertEquals("target", vm.state.value.savedId)
    }

    @Test
    fun groupedSaveRetryContinuesAfterLastSuccessfulMove() = runTest(dispatcher) {
        documents.setDraftPages(listOf("leading", "a", "sep1", "b", "sep2", "c", "trailing"))
        processing.blanks = setOf("leading", "sep1", "sep2", "trailing")
        val vm = review()
        runCurrent()
        vm.splitAtBlanks(duplex = false)
        runCurrent()
        documents.failNextSplitAt = 1

        vm.save("")
        runCurrent()
        assertTrue(vm.state.value.finishPending)
        assertEquals(1, documents.splitDocuments.size)
        assertTrue(documents.deletedIds.isEmpty())

        vm.save("")
        runCurrent()
        assertEquals("draft", vm.state.value.savedId)
        assertEquals(listOf("leading", "a"), documents.draft.pages.map { it.id })
        assertEquals(listOf(listOf("b"), listOf("c", "trailing")), documents.splitDocuments.map { doc ->
            doc.pages.map { it.id }
        })
        assertEquals(listOf("sep1", "sep2"), documents.deletedIds)
    }

    @Test
    fun changedSeparatorBlocksSaveAndKeepsAllPages() = runTest(dispatcher) {
        val ids = listOf("first", "sep", "second")
        documents.setDraftPages(ids)
        processing.blanks = setOf("sep")
        val vm = review()
        runCurrent()
        vm.splitAtBlanks(duplex = false)
        runCurrent()
        processing.blanks = emptySet()

        vm.save("")
        runCurrent()

        assertEquals(ids, documents.draft.pages.map { it.id })
        assertTrue(documents.splitDocuments.isEmpty())
        assertTrue(documents.deletedIds.isEmpty())
        assertEquals(R.string.review_separators_changed, vm.state.value.message)
    }

    @Test
    fun deskewAndInkRemovalArePreviewEditsThatUndoRedoAndSave() = runTest(dispatcher) {
        val vm = editor()
        runCurrent()
        assertTrue(requireNotNull(vm.state.value.edits).deskew)

        vm.edit { it.copy(deskew = false) }
        vm.edit { it.copy(inkRemoval = InkRemoval.BLUE) }
        vm.undo()
        assertEquals(null, vm.state.value.edits?.inkRemoval)
        vm.undo()
        assertFalse(vm.state.value.dirty)

        vm.redo()
        vm.redo()
        val working = requireNotNull(vm.state.value.edits).applyTo(requireNotNull(vm.state.value.page))
        vm.preview(working, 1200)
        assertEquals(working, processing.previewed)
        vm.done()
        runCurrent()

        assertFalse(documents.updates.single().deskew)
        assertEquals(InkRemoval.BLUE, documents.updates.single().inkRemoval)
        assertEquals(listOf("first"), processing.renderedIds)
    }

    @Test
    fun viewerStartsOnRequestedPageAndMapsProcessingById() = runTest(dispatcher) {
        val vm = viewer("second")
        processing.busyPageIds.value = setOf("second", "unrelated")
        runCurrent()

        assertEquals(1, vm.state.value.selectedIndex)
        assertEquals(listOf(false, true), vm.state.value.pages.map { it.busy })
        processing.busyPageIds.value = emptySet()
        runCurrent()
        assertEquals(listOf(false, false), vm.state.value.pages.map { it.busy })
    }

    @Test
    fun viewerRotatesClockwiseAndEnqueuesRender() = runTest(dispatcher) {
        val vm = viewer("second")
        runCurrent()

        vm.rotate("second")
        assertTrue(vm.state.value.changing)
        vm.rotate("second")
        runCurrent()

        assertEquals(listOf(listOf("second")), processing.waits)
        assertEquals(listOf("second" to 90), documents.rotations)
        assertEquals(listOf("second"), processing.renderedIds)
        assertFalse(vm.state.value.changing)
    }

    @Test
    fun viewerDeleteSelectsNextPageOrPreviousAtEndAndClosesWhenEmpty() = runTest(dispatcher) {
        documents.setDraftPages(listOf("first", "second", "third"))
        val vm = viewer("second")
        runCurrent()

        vm.delete("second")
        runCurrent()
        assertEquals(listOf("second"), documents.deletedIds)
        assertEquals("third", vm.state.value.pages[vm.state.value.selectedIndex].page.id)
        assertFalse(vm.state.value.close)

        vm.delete("third")
        runCurrent()
        assertEquals("first", vm.state.value.pages[vm.state.value.selectedIndex].page.id)

        vm.delete("first")
        runCurrent()
        assertTrue(vm.state.value.pages.isEmpty())
        assertTrue(vm.state.value.close)
        assertFalse(vm.state.value.changing)
    }

    @Test
    fun viewerOpensSavedDocumentsAndFallsBackToFirstPageForMissingStartId() = runTest(dispatcher) {
        val vm = viewer("missing", "target")
        runCurrent()

        assertEquals("existing", vm.state.value.pages.single().page.id)
        assertEquals(0, vm.state.value.selectedIndex)
        assertFalse(vm.state.value.close)
    }

    @Test
    fun pageImagesAreNamedAfterTheDocument() {
        assertEquals("Biology notes p3.jpg", pageFileName("Biology notes", 3, 5))
        assertEquals("Receipt.jpg", pageFileName("Receipt", 1, 1))
        assertEquals("a_b p1.jpg", pageFileName("a/b", 1, 2))
        assertEquals("Scan.jpg", pageFileName("  ", 1, 1))
    }

    @Test
    fun savingAPageWithoutARenderedImageReportsFailure() = runTest(dispatcher) {
        val vm = viewer("missing", "target")
        runCurrent()

        vm.exportPage("existing", share = false)
        runCurrent()

        assertTrue(saved.isEmpty())
        assertEquals(R.string.viewer_export_failed, vm.state.value.message)
        assertFalse(vm.state.value.changing)
    }

    private fun viewer(pageId: String, documentId: String = "draft") =
        PageViewerViewModel(documentId, pageId, documents, processing, sink, object : dev.turjo.paperless.core.data.ScanPageText {
            override val automatic = kotlinx.coroutines.flow.flowOf(true)
            override fun observe(pageId: String) = kotlinx.coroutines.flow.flowOf(null)
            override suspend fun recognize(pageId: String, image: File?, hash: String?) = null
        }).also(viewModels::add)

    private fun review(savedState: SavedStateHandle = SavedStateHandle()) = ReviewViewModel(
        "draft", documents, sessions, processing, { id -> File("$id.webp") }, dispatcher, savedState,
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

        fun addFakePage(page: Page) {
            draftFlow.value = draft.copy(pages = draft.pages + page)
        }
        val target: DocumentWithPages get() = targetFlow.value
        val updates = mutableListOf<Page>()
        val commits = mutableListOf<Commit>()
        val splitDocuments = mutableListOf<DocumentWithPages>()
        val deletedIds = mutableListOf<String>()
        val rotations = mutableListOf<Pair<String, Int>>()
        var failNextSplitAt: Int? = null

        fun setDraftPages(ids: List<String>) {
            draftFlow.value = draft.copy(pages = ids.mapIndexed { index, id -> page(id, index) })
        }

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
        override suspend fun splitOff(draftId: String, pageIds: List<String>, title: String): String {
            require(draftId == draft.document.id)
            if (splitDocuments.size == failNextSplitAt) {
                failNextSplitAt = null
                error("Simulated split failure")
            }
            val id = "split-${splitDocuments.size}"
            val moved = pageIds.mapIndexed { index, pageId ->
                draft.pages.first { it.id == pageId }.copy(documentId = id, index = index)
            }
            splitDocuments += DocumentWithPages(document(id, title, false), moved)
            keepDraftPages(draft.pages.filterNot { it.id in pageIds })
            return id
        }

        override suspend fun deletePage(pageId: String) {
            deletedIds += pageId
            keepDraftPages(draft.pages.filterNot { it.id == pageId })
        }

        private fun keepDraftPages(pages: List<Page>) {
            draftFlow.value = draft.copy(pages = pages.mapIndexed { index, page -> page.copy(index = index) })
        }
        override suspend fun idComposite(pageId: String): IdComposite? =
            if (pageId == "id-composite-second") IdComposite(pageId, "first", "second", IdCardLayout()) else null

        override suspend fun rotatePage(pageId: String, delta: Int) {
            rotations += pageId to delta
        }
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
        var blanks = emptySet<String>()
        var previewed: Page? = null

        val layouts = mutableListOf<Pair<String, IdCardLayout>>()
        var onLayout: () -> Unit = {}

        override suspend fun updateIdLayout(pageId: String, layout: IdCardLayout): Boolean {
            layouts += pageId to layout
            onLayout()
            return true
        }

        override suspend fun blankInfo(pageIds: List<String>) = pageIds.map { PageBlankInfo(it, it in blanks) }

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
        override suspend fun previewBitmap(page: Page, maxSide: Int): Bitmap {
            previewed = page
            error("No bitmap in the JVM test")
        }
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
