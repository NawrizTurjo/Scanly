package dev.turjo.paperless.feature.library

import android.net.Uri
import androidx.lifecycle.viewModelScope
import app.cash.turbine.test
import dev.turjo.paperless.core.data.DocumentRepository
import dev.turjo.paperless.core.data.FolderRepository
import dev.turjo.paperless.core.data.ReaderRepository
import dev.turjo.paperless.core.model.DeletedDocument
import dev.turjo.paperless.core.model.DocumentSummary
import dev.turjo.paperless.core.model.FolderSummary
import dev.turjo.paperless.core.model.LibraryFilter
import dev.turjo.paperless.core.model.Page
import dev.turjo.paperless.core.model.PdfKey
import dev.turjo.paperless.core.model.RecentPdf
import dev.turjo.paperless.core.model.TabState
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
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
        val viewModel = LibraryViewModel(repository, TestFolders(), TestReaderRepository())
        val document = DocumentSummary("doc", "Receipt", 1, null, 100, true)

        viewModel.uiState.test {
            assertEquals(LibraryUiState(), awaitItem())
            assertEquals(LibraryUiState(isLoading = false), awaitItem())
            repository.library.value = listOf(document)
            assertEquals(LibraryUiState(listOf(document), isLoading = false), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun continueReadingShowsFiveReadableEntriesWithOneBasedClampedProgress() = runTest(dispatcher) {
        val reader = TestReaderRepository()
        val model = LibraryViewModel(FakeRepository(), TestFolders(), reader)
        val pdfs = listOf(
            RecentPdf(PdfKey("unknown"), "Unknown page count", 7, 0, 0),
            RecentPdf(PdfKey("pdf"), "Manual", 6, 340, 11),
            RecentPdf(PdfKey("doc:scan"), "Scan", 5, 10, 100),
            RecentPdf(PdfKey("start"), "Start", 4, 10, -1),
            RecentPdf(PdfKey("fourth"), "Fourth", 3, 20, 1),
            RecentPdf(PdfKey("fifth"), "Fifth", 2, 20, 2),
            RecentPdf(PdfKey("sixth"), "Sixth", 1, 20, 3),
        )

        try {
            model.continueReading.test {
                assertEquals(emptyList<ContinueReadingItem>(), awaitItem())
                reader.recent.value = pdfs
                val items = awaitItem()
                assertEquals(pdfs.subList(1, 6).map(RecentPdf::pdfKey), items.map(ContinueReadingItem::pdfKey))
                assertEquals("Manual", items.first().title)
                assertEquals(340, items.first().pageCount)
                assertEquals(listOf(12, 10, 1, 2, 3), items.map(ContinueReadingItem::page))
                assertEquals(12f / 340, items.first().progress, 0.0001f)
                assertEquals(1f, items[1].progress, 0.0001f)
                reader.recent.value = emptyList()
                assertEquals(emptyList<ContinueReadingItem>(), awaitItem())
            }
        } finally {
            model.viewModelScope.cancel()
        }
    }

    @Test
    fun deletingSelectionEmitsUndoForExactlyThoseDocuments() = runTest(dispatcher) {
        val repository = FakeRepository()
        val model = LibraryViewModel(repository, TestFolders(), TestReaderRepository())
        val first = DocumentSummary("first", "First", 1, null, 0, false)
        val second = first.copy(id = "second")
        val excluded = first.copy(id = "excluded")
        model.toggleSelection(first)
        model.toggleSelection(second)
        model.toggleSelection(excluded)
        model.toggleSelection(excluded)

        model.notices.test {
            model.deleteSelected()
            val deleted = awaitItem() as LibraryNotice.Deleted
            assertEquals(setOf("first", "second"), deleted.ids)
            assertEquals(deleted.ids, repository.deletedIds)
            assertEquals(emptyMap<String, Boolean>(), model.selection.value)
            model.undoDelete(deleted.ids)
            runCurrent()
            assertEquals(emptySet<String>(), repository.deletedIds)
        }
        assertEquals(30, repository.purgedAfterDays)
    }

    @Test
    fun mixedSelectionFavouritesAllAndAllFavouritesAreCleared() = runTest(dispatcher) {
        val repository = FakeRepository()
        val model = LibraryViewModel(repository, TestFolders(), TestReaderRepository())
        val first = DocumentSummary("first", "First", 1, null, 0, false)
        val second = first.copy(id = "second", isFavorite = true)
        model.toggleSelection(first)
        model.toggleSelection(second)
        model.favoriteSelected()
        runCurrent()
        assertEquals(mapOf("first" to true, "second" to true), repository.favorites)

        model.toggleSelection(first.copy(isFavorite = true))
        model.toggleSelection(second)
        model.favoriteSelected()
        runCurrent()
        assertEquals(mapOf("first" to false, "second" to false), repository.favorites)
        assertEquals(emptyMap<String, Boolean>(), model.selection.value)
    }

    @Test
    fun movesSelectionToExistingAndNewFolders() = runTest(dispatcher) {
        val folders = TestFolders()
        val model = LibraryViewModel(FakeRepository(), folders, TestReaderRepository())
        val document = DocumentSummary("doc", "Receipt", 1, null, 0, false)
        model.toggleSelection(document)
        model.moveSelected("existing")
        runCurrent()
        assertEquals(setOf("doc") to "existing", folders.lastMove)

        model.toggleSelection(document)
        model.moveSelectedToNewFolder("Receipts")
        runCurrent()
        assertEquals("Receipts", folders.createdName)
        assertEquals(setOf("doc") to "new", folders.lastMove)
        assertEquals(emptyMap<String, Boolean>(), model.selection.value)
    }

    private class TestReaderRepository : ReaderRepository {
        val recent = MutableStateFlow<List<RecentPdf>>(emptyList())

        override fun observeRecent() = recent
        override fun observeTabs() = error("Unexpected tab query")
        override fun observeActiveTabId() = error("Unexpected active tab query")
        override suspend fun saveTabs(tabs: List<TabState>, activeTabId: String?): Unit = error("Unexpected tab save")
        override suspend fun registerExternal(uri: Uri, displayName: String): PdfKey = error("Unexpected registration")
        override suspend fun pdfKeyForDocument(documentId: String): PdfKey = error("Unexpected export")
        override suspend fun markOpened(key: PdfKey, pageCount: Int, lastPage: Int): Unit = error("Unexpected open")
    }

    internal open class FakeRepository : DocumentRepository {
        val library = MutableStateFlow<List<DocumentSummary>>(emptyList())

        val deletedIds = mutableSetOf<String>()
        val favorites = mutableMapOf<String, Boolean>()
        var purgedAfterDays: Int? = null

        override fun observeLibrary(filter: LibraryFilter) = library
        override fun observeDeleted() = flowOf(emptyList<DeletedDocument>())
        override suspend fun purgeDeletedOlderThan(days: Int) { purgedAfterDays = days }
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
        override suspend fun setFavorite(id: String, isFavorite: Boolean) { favorites[id] = isFavorite }
        override suspend fun softDelete(id: String) { deletedIds.add(id) }
        override suspend fun restore(id: String) { deletedIds.remove(id) }
        override suspend fun commitDraft(draftId: String, title: String, targetDocumentId: String?, insertAt: Int?): Nothing =
            unsupported()

        private fun unsupported(): Nothing = error("Unexpected repository call")
    }
}

internal class TestFolders : FolderRepository {
    var createdName: String? = null
    var lastMove: Pair<Set<String>, String?>? = null

    override fun observeFolders() = flowOf(emptyList<FolderSummary>())
    override suspend fun create(name: String, parentId: String?): String {
        createdName = name
        return "new"
    }
    override suspend fun rename(id: String, name: String): Unit = error("Unexpected rename")
    override suspend fun delete(id: String): Unit = error("Unexpected delete")
    override suspend fun moveDocuments(documentIds: Set<String>, folderId: String?) {
        lastMove = documentIds to folderId
    }
}
