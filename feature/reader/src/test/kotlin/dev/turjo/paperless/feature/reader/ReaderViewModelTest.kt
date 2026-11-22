package dev.turjo.paperless.feature.reader

import android.graphics.Bitmap
import android.net.Uri
import android.util.SizeF
import androidx.lifecycle.viewModelScope
import dev.turjo.paperless.core.data.PdfSaveService
import dev.turjo.paperless.core.data.SaveResult
import dev.turjo.paperless.core.data.ReaderRepository
import dev.turjo.paperless.core.model.OutlineNode
import dev.turjo.paperless.core.model.PdfKey
import dev.turjo.paperless.core.model.ReaderViewMode
import dev.turjo.paperless.core.model.RecentPdf
import dev.turjo.paperless.core.model.TabState
import dev.turjo.paperless.core.pdf.PdfDocumentHandle
import dev.turjo.paperless.core.pdf.PdfOpenException
import dev.turjo.paperless.core.pdf.PdfSessionManager
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repository = FakeReaderRepository()
    private val sessions = FakePdfSessions()
    private val viewModels = mutableListOf<ReaderViewModel>()

    @get:Rule val files = TemporaryFolder()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        viewModels.forEach { it.viewModelScope.cancel() }
        dispatcher.scheduler.advanceUntilIdle()
        Dispatchers.resetMain()
    }

    @Test
    fun eightTabsKeepIndependentStateWhenSwitchingAndClosing() = runTest(dispatcher) {
        val vm = newViewModel()
        repeat(8) { index ->
            vm.open(pdf(index), "PDF ${index + 1}")
            runCurrent()
            val id = checkNotNull(vm.uiState.value.activeTabId)
            if (index % 2 == 0) vm.toggleNightMode(id)
            if (index == 2) vm.toggleViewMode(id)
            vm.updatePosition(id, index + 4, index * 13)
            vm.updateZoom(id, 1f + index / 2f, index * 9f)
        }
        val originals = vm.uiState.value.tabs
        assertEquals(8, originals.size)

        for ((index, tab) in originals.withIndex().reversed()) {
            vm.switch(tab.tabId)
            runCurrent()
            val active = checkNotNull(vm.uiState.value.activeTab)
            assertEquals(pdf(index), active.pdfKey)
            assertEquals(index + 4, active.page)
            assertEquals(index * 13, active.scrollOffsetPx)
            assertEquals(1f + index / 2f, active.zoom, 0f)
            assertEquals(index * 9f, vm.horizontalOffset(tab.tabId), 0f)
            assertEquals(index % 2 == 0, active.nightMode)
            assertEquals(
                if (index == 2) ReaderViewMode.SINGLE_PAGE else ReaderViewMode.CONTINUOUS,
                active.viewMode,
            )
        }

        vm.switch(originals[3].tabId)
        vm.close(originals[3].tabId)
        runCurrent()
        assertEquals(originals[4], vm.uiState.value.activeTab)
        assertEquals(originals - originals[3], vm.uiState.value.tabs)
        vm.switch(originals.last().tabId)
        vm.close(originals.last().tabId)
        runCurrent()
        assertEquals(originals[6], vm.uiState.value.activeTab)
        assertEquals(originals - originals[3] - originals.last(), vm.uiState.value.tabs)
    }

    @Test
    fun incomingRecentKeyWaitsForInitialProgressBeforeOpening() = runTest(dispatcher) {
        repository.recent.value = null
        val vm = newViewModel()
        val request = async { vm.open(pdf(0)) }
        runCurrent()
        assertTrue(vm.uiState.value.tabs.isEmpty())

        repository.recent.value = listOf(RecentPdf(pdf(0), "Manual", 1, 100, 11))
        request.await()
        runCurrent()

        assertEquals("Manual", vm.uiState.value.activeTab?.title)
        assertEquals(11, vm.uiState.value.activeTab?.page)
    }

    @Test
    fun reopeningTheSameKeyFocusesItsExistingTab() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.open(pdf(0), "Original title")
        runCurrent()
        val firstId = checkNotNull(vm.uiState.value.activeTabId)
        vm.updatePosition(firstId, 19, 32)
        vm.updateZoom(firstId, 2.5f, 67f)
        vm.open(pdf(1), "Another PDF")
        runCurrent()

        vm.open(pdf(0), "New title", page = 0)
        runCurrent()

        assertEquals(2, vm.uiState.value.tabs.size)
        assertEquals(
            TabState(firstId, pdf(0), "Original title", page = 19, zoom = 2.5f, scrollOffsetPx = 32),
            vm.uiState.value.activeTab,
        )
        assertNotNull(vm.uiState.value.document)
    }

    @Test
    fun aFreshViewModelRestoresTabsAndActiveSelectionAfterDebouncedSave() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.open(pdf(0), "First")
        runCurrent()
        val firstId = checkNotNull(vm.uiState.value.activeTabId)
        vm.updatePosition(firstId, 31, 125)
        vm.updateZoom(firstId, 4f, 20f)
        vm.toggleNightMode(firstId)
        vm.open(pdf(1), "Second")
        runCurrent()
        val secondId = checkNotNull(vm.uiState.value.activeTabId)
        vm.toggleViewMode(secondId)
        vm.updatePosition(secondId, 5, 0)
        vm.switch(firstId)
        runCurrent()
        val expectedTabs = vm.uiState.value.tabs

        advanceTimeBy(499)
        runCurrent()
        assertEquals(0, repository.saves)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, repository.saves)
        assertEquals(100 to 31, repository.progress[pdf(0)])
        assertEquals(100 to 5, repository.progress[pdf(1)])
        vm.viewModelScope.cancel()
        runCurrent()

        val restored = newViewModel()
        runCurrent()

        assertEquals(expectedTabs, restored.uiState.value.tabs)
        assertEquals(firstId, restored.uiState.value.activeTabId)
        assertEquals(expectedTabs.first(), restored.uiState.value.activeTab)
        assertNotNull(restored.uiState.value.document)
    }

    @Test
    fun rapidSwitchesKeepHandleRequestsBoundedAndPublishTheFinalTab() = runTest(dispatcher) {
        val vm = newViewModel()
        repeat(8) { vm.open(pdf(it)) }
        runCurrent()
        val tabs = vm.uiState.value.tabs
        sessions.openDelayMs = 100

        // Return to the original tab before the session collector observes the intermediate selection.
        vm.switch(tabs.first().tabId)
        vm.switch(tabs.last().tabId)
        runCurrent()
        advanceTimeBy(100)
        runCurrent()
        assertNotNull(vm.uiState.value.document)

        for (tab in tabs.dropLast(1)) {
            vm.switch(tab.tabId)
            runCurrent()
            advanceTimeBy(20)
        }
        advanceTimeBy(300)
        runCurrent()

        assertTrue("Peak handle requests were ${sessions.peakRequests}", sessions.peakRequests in 1..2)
        assertEquals(tabs[6].tabId, vm.uiState.value.activeTabId)
        assertNotNull(vm.uiState.value.document)
        assertEquals(setOf(tabs[6].pdfKey), sessions.requests.keys)
        vm.close(tabs[6].tabId)
        runCurrent()
        assertNull(vm.uiState.value.document)
        assertEquals(setOf(tabs.last().pdfKey), sessions.requests.keys)
    }

    @Test
    fun encryptedAndCorruptPdfsExposeDifferentErrors() = runTest(dispatcher) {
        sessions.failures[pdf(0)] = PdfOpenException.Encrypted(SecurityException())
        sessions.failures[pdf(1)] = PdfOpenException.Corrupt(IOException())
        val vm = newViewModel()

        vm.open(pdf(0))
        runCurrent()
        assertEquals(ReaderError.ENCRYPTED, vm.uiState.value.openError)
        assertNull(vm.uiState.value.document)

        vm.open(pdf(1))
        runCurrent()
        assertEquals(ReaderError.OPEN, vm.uiState.value.openError)
        assertNull(vm.uiState.value.document)

        vm.open(pdf(2))
        runCurrent()
        assertNull(vm.uiState.value.openError)
        assertNotNull(vm.uiState.value.document)
    }

    @Test
    fun outlineSaveWritesBeforeCallingServiceAndKeepsTabOpenOnFailure() = runTest(dispatcher) {
        val input = files.newFile()
        val bookmarks = listOf(OutlineNode("Chapter", 1))
        val key = pdf(0)
        val calls = mutableListOf<String>()
        val service = object : PdfSaveService {
            override suspend fun replaceExternal(key: PdfKey, newFile: File): SaveResult {
                assertEquals(pdf(0), key)
                assertEquals("edited PDF", newFile.readText())
                calls += "overwrite"
                return SaveResult.NotEditable
            }

            override suspend fun saveCopy(key: PdfKey, newFile: File): SaveResult {
                assertEquals(pdf(0), key)
                assertEquals("edited PDF", newFile.readText())
                calls += "copy"
                return SaveResult.Failed(IOException("Storage full"))
            }
        }
        val outputs = mutableListOf<File>()
        val outlineFiles = ReaderOutlineFiles(
            source = { ReaderOutlineFiles.Source(input) },
            output = { files.newFile().also(outputs::add) },
            write = { source, nodes, output ->
                assertEquals(input, source)
                assertEquals(bookmarks, nodes)
                output.writeText("edited PDF")
                calls += "write"
            },
            saves = service,
        )
        val vm = ReaderViewModel(repository, sessions, outlineFiles).also(viewModels::add)
        vm.open(key, page = 1)
        runCurrent()
        val original = checkNotNull(vm.uiState.value.activeTab)
        val originalHandle = vm.uiState.value.document
        assertEquals(false, vm.saveOutline(original.tabId, bookmarks, overwrite = true))
        assertEquals(false, vm.saveOutline(original.tabId, bookmarks, overwrite = false))
        assertEquals(listOf("write", "overwrite", "write", "copy"), calls)
        assertEquals(original, vm.uiState.value.activeTab)
        assertEquals(originalHandle, vm.uiState.value.document)
        assertTrue(outputs.none(File::exists))
        assertTrue(input.exists())
    }

    private fun newViewModel() = ReaderViewModel(repository, sessions).also(viewModels::add)

    private fun pdf(index: Int) = PdfKey("pdf-$index")

    private class FakeReaderRepository : ReaderRepository {
        override suspend fun resolveUri(key: dev.turjo.paperless.core.model.PdfKey): android.net.Uri =
            throw UnsupportedOperationException("not used in this test")
        private val saved = MutableStateFlow(emptyList<TabState>() to (null as String?))
        var saves = 0
            private set
        val progress = mutableMapOf<PdfKey, Pair<Int, Int>>()

        override fun observeTabs() = saved.map { it.first }

        override fun observeActiveTabId() = saved.map { it.second }

        override suspend fun saveTabs(tabs: List<TabState>, activeTabId: String?) {
            saves++
            saved.value = tabs to activeTabId
        }

        val recent = MutableStateFlow<List<RecentPdf>?>(emptyList())

        override fun observeRecent() = recent.filterNotNull()

        override suspend fun registerExternal(uri: Uri, displayName: String): PdfKey =
            error("The reader does not use the system picker in this test")

        override suspend fun pdfKeyForDocument(documentId: String) = PdfKey("doc:$documentId")

        override suspend fun markOpened(key: PdfKey, pageCount: Int, lastPage: Int) {
            progress[key] = pageCount to lastPage
        }
    }

    private class FakePdfSessions : PdfSessionManager {
        val requests = mutableMapOf<PdfKey, Int>()
        val failures = mutableMapOf<PdfKey, PdfOpenException>()
        var peakRequests = 0
            private set
        var openDelayMs = 0L

        override suspend fun open(key: PdfKey): PdfDocumentHandle {
            requests[key] = (requests[key] ?: 0) + 1
            peakRequests = maxOf(peakRequests, requests.values.sum())
            if (openDelayMs > 0) withContext(NonCancellable) { delay(openDelayMs) }
            failures[key]?.let { throw it }
            return object : PdfDocumentHandle {
                override val pageCount = 100
                override suspend fun pageSize(index: Int): SizeF = error("Page layout belongs to the UI")
                override suspend fun render(index: Int, widthPx: Int, night: Boolean): Bitmap =
                    error("Page rendering belongs to the UI")
            }
        }

        override fun release(key: PdfKey) {
            val count = checkNotNull(requests[key])
            if (count == 1) requests.remove(key) else requests[key] = count - 1
        }
    }
}

