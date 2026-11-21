package dev.turjo.paperless.feature.library

import androidx.lifecycle.viewModelScope
import dev.turjo.paperless.core.data.PageProcessing
import dev.turjo.paperless.core.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DocumentViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val documents = FakeRepository()
    private lateinit var model: DocumentViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        model = DocumentViewModel("doc", documents, IdleProcessing, { error("Unexpected thumbnail") }) {
            error("Unexpected export")
        }
    }

    @After
    fun tearDown() {
        model.viewModelScope.cancel()
        Dispatchers.resetMain()
    }

    @Test
    fun renameTrimsTheTitle() = runTest(dispatcher) {
        model.rename("  New title  ")
        runCurrent()
        assertEquals("New title", model.state.value.document?.document?.title)
    }

    @Test
    fun reorderPersistsTheNewPageSequence() = runTest(dispatcher) {
        model.reorder(listOf("two", "one"))
        runCurrent()
        assertEquals(listOf("two", "one"), model.state.value.document?.pages?.map(Page::id))
        assertFalse(model.state.value.savingOrder)
    }

    @Test
    fun lastPageRequiresConfirmationAndSoftDeletesTheDocument() = runTest(dispatcher) {
        documents.document.value = documents.document.value.copy(pages = listOf(page("one", 0)))
        model.deletePage("one")
        runCurrent()
        assertNotNull(model.state.value.document)
        assertNull(documents.document.value.document.deletedAt)

        model.deletePage("one", confirmedLastPage = true)
        runCurrent()
        assertNull(model.state.value.document)
        assertNotNull(documents.document.value.document.deletedAt)
        assertEquals(listOf("one"), documents.document.value.pages.map(Page::id))
    }

    @Test
    fun afterPageUsesTheOneBasedPageNumberAsZeroBasedInsertionIndex() {
        assertEquals(1, insertAfterPage(1, 4))
        assertEquals(4, insertAfterPage(4, 4))
        assertNull(insertAfterPage(0, 4))
        assertNull(insertAfterPage(5, 4))
        assertNull(insertAfterPage(1, 0))
    }

    private class FakeRepository : LibraryViewModelTest.FakeRepository() {
        val document = MutableStateFlow(
            DocumentWithPages(Document("doc", "Receipt", null, false, false, 0, 0, null, null), listOf(page("one", 0), page("two", 1))),
        )

        override fun observeDocument(id: String) = document

        override suspend fun rename(id: String, title: String) {
            val current = document.value
            document.value = current.copy(document = current.document.copy(title = title))
        }

        override suspend fun reorderPages(documentId: String, orderedPageIds: List<String>) {
            val current = document.value
            val pages = current.pages.associateBy(Page::id)
            document.value = current.copy(pages = orderedPageIds.mapIndexed { index, id ->
                pages.getValue(id).copy(index = index)
            })
        }

        override suspend fun softDelete(id: String) {
            val current = document.value
            document.value = current.copy(document = current.document.copy(deletedAt = 1))
        }
    }

    private object IdleProcessing : PageProcessing {
        override val busyPageIds = MutableStateFlow(emptySet<String>())
        override fun enqueueNewPage(pageId: String, filter: PageFilter) = error("Unexpected processing")
        override fun enqueueRender(pageId: String) = error("Unexpected processing")
        override suspend fun awaitIdle(pageIds: Collection<String>) = Unit
        override suspend fun detectQuad(pageId: String) = error("Unexpected processing")
        override suspend fun originalBitmap(pageId: String, maxSide: Int) = error("Unexpected processing")
        override suspend fun previewBitmap(page: Page, maxSide: Int) = error("Unexpected processing")
        override suspend fun uprightSize(pageId: String) = error("Unexpected processing")
    }
}

private fun page(id: String, index: Int, original: String = "unused") = Page(
    id, "doc", index, PageMode.NORMAL, original, 100, 200, null, 0,
    PageFilter.DOCUMENT, 0f, 0f, false, false, null, null, false, 0,
)
