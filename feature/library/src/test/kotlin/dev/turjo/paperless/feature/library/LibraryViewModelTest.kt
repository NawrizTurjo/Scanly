package dev.turjo.paperless.feature.library

import app.cash.turbine.test
import dev.turjo.paperless.core.data.DocumentRepository
import dev.turjo.paperless.core.model.DocumentSummary
import dev.turjo.paperless.core.model.Page
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class LibraryViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun stateReflectsInitialAndUpdatedLibrary() = runTest(dispatcher) {
        val repository = FakeRepository()
        val viewModel = LibraryViewModel(repository)
        val document = DocumentSummary("doc", "Receipt", 1, null, 100, true)

        viewModel.uiState.test {
            assertEquals(LibraryUiState(), awaitItem())
            assertEquals(LibraryUiState(isLoading = false), awaitItem())
            repository.library.value = listOf(document)
            assertEquals(LibraryUiState(listOf(document), isLoading = false), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    internal open class FakeRepository : DocumentRepository {
        val library = MutableStateFlow<List<DocumentSummary>>(emptyList())

        override fun observeLibrary() = library
        override fun observeDocument(id: String): kotlinx.coroutines.flow.Flow<dev.turjo.paperless.core.model.DocumentWithPages?> =
            unsupported()
        override suspend fun getPage(id: String): Nothing = unsupported()
        override suspend fun updatePage(page: Page): Nothing = unsupported()
        override suspend fun addPage(documentId: String, originalFile: File, width: Int, height: Int, at: Int?): Nothing =
            unsupported()

        override suspend fun reorderPages(documentId: String, orderedPageIds: List<String>): Unit = unsupported()
        override suspend fun deletePage(pageId: String): Unit = unsupported()
        override suspend fun rotatePage(pageId: String, delta: Int): Nothing = unsupported()
        override suspend fun rename(id: String, title: String): Unit = unsupported()
        override suspend fun setFavorite(id: String, isFavorite: Boolean): Nothing = unsupported()
        override suspend fun softDelete(id: String): Unit = unsupported()
        override suspend fun restore(id: String): Nothing = unsupported()
        override suspend fun commitDraft(draftId: String, title: String, targetDocumentId: String?, insertAt: Int?): Nothing =
            unsupported()

        private fun unsupported(): Nothing = error("Unexpected repository call")
    }
}
