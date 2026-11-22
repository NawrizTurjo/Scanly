package dev.turjo.paperless.feature.reader

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.turjo.paperless.core.data.ReaderRepository
import dev.turjo.paperless.core.model.PdfKey
import dev.turjo.paperless.core.model.ReaderViewMode
import dev.turjo.paperless.core.model.RecentPdf
import dev.turjo.paperless.core.model.TabState
import dev.turjo.paperless.core.pdf.PdfDocumentHandle
import dev.turjo.paperless.core.pdf.PdfOpenException
import dev.turjo.paperless.core.pdf.PdfSessionManager
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

data class ReaderUiState(
    val restoring: Boolean = true,
    internal val selectionRevision: Long = 0,
    val tabs: List<TabState> = emptyList(),
    val activeTabId: String? = null,
    val recent: List<RecentPdf> = emptyList(),
    val document: PdfDocumentHandle? = null,
    val openError: ReaderError? = null,
    val notice: ReaderError? = null,
) {
    val activeTab: TabState? get() = tabs.firstOrNull { it.tabId == activeTabId }
}

enum class ReaderError { ENCRYPTED, OPEN, SAVE }

@OptIn(FlowPreview::class)
@HiltViewModel
class ReaderViewModel @Inject constructor(
    private val repository: ReaderRepository,
    private val sessions: PdfSessionManager,
) : ViewModel() {
    private val state = MutableStateFlow(ReaderUiState())
    val uiState = state.asStateFlow()
    private val horizontalOffsets = mutableMapOf<String, Float>()
    private class ReadingProgress(val count: Int, val page: Int)

    private val pendingProgress = MutableStateFlow<Map<PdfKey, ReadingProgress>>(emptyMap())
    private var savedTabs = emptyList<TabState>() to (null as String?)

    init {
        viewModelScope.launch {
            val (tabs, activeId) = attempt(ReaderError.OPEN) {
                combine(repository.observeTabs(), repository.observeActiveTabId(), ::Pair).first()
            } ?: (emptyList<TabState>() to null)
            val selected = tabs.firstOrNull { it.tabId == activeId }?.tabId ?: tabs.firstOrNull()?.tabId
            savedTabs = tabs to activeId
            select(tabs, selected, restoring = false)
        }
        viewModelScope.launch {
            attempt(ReaderError.OPEN) {
                repository.observeRecent().collect { state.value = state.value.copy(recent = it) }
            }
        }
        viewModelScope.launch {
            state.map { current ->
                current.activeTab?.let { Triple(current.selectionRevision, it.tabId, it.pdfKey) }
            }.distinctUntilChanged().collectLatest { selected ->
                if (selected == null) return@collectLatest
                val (revision, id, key) = selected
                try {
                    val document = sessions.open(key)
                    if (state.value.selectionRevision != revision) return@collectLatest
                    require(document.pageCount > 0)
                    val current = state.value
                    val tab = checkNotNull(current.activeTab)
                    val page = tab.page.coerceIn(0, document.pageCount - 1)
                    val restored = if (page == tab.page) tab else tab.copy(page = page, scrollOffsetPx = 0)
                    state.value = current.copy(
                        tabs = current.tabs.map { if (it.tabId == id) restored else it },
                        document = document,
                    )
                    recordProgress(key, document.pageCount, page)
                    awaitCancellation()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    if (state.value.selectionRevision == revision) {
                        state.value = state.value.copy(
                            openError = if (error is PdfOpenException.Encrypted) ReaderError.ENCRYPTED else ReaderError.OPEN,
                        )
                    }
                } finally {
                    sessions.release(key)
                }
            }
        }
        viewModelScope.launch {
            state.filter { !it.restoring }
                .map { it.tabs to it.activeTabId }
                .distinctUntilChanged()
                .debounce(500)
                .collect { snapshot ->
                    if (snapshot != savedTabs) {
                        attempt(ReaderError.SAVE) {
                            repository.saveTabs(snapshot.first, snapshot.second)
                            savedTabs = snapshot
                        }
                    }
                }
        }
        viewModelScope.launch {
            pendingProgress.debounce(500).collect { snapshot ->
                for ((key, progress) in snapshot) {
                    if (pendingProgress.value[key] !== progress) continue
                    attempt(ReaderError.SAVE) {
                        repository.markOpened(key, progress.count, progress.page)
                        if (pendingProgress.value[key] === progress) {
                            pendingProgress.value = pendingProgress.value - key
                        }
                    }
                }
            }
        }
    }

    suspend fun open(key: PdfKey, title: String? = null, page: Int? = null) {
        state.first { !it.restoring }
        val current = state.value
        val existing = current.tabs.firstOrNull { it.pdfKey == key }
        if (existing != null) {
            switch(existing.tabId)
            return
        }
        val recent = current.recent.firstOrNull { it.pdfKey == key }
        val tab = TabState(UUID.randomUUID().toString(), key, title ?: recent?.title ?: "PDF", page ?: recent?.lastPage ?: 0)
        select(current.tabs + tab, tab.tabId)
    }

    fun openExternal(uri: Uri, title: String) {
        viewModelScope.launch {
            val key = attempt(ReaderError.OPEN) { repository.registerExternal(uri, title) } ?: return@launch
            open(key, title)
        }
    }

    fun openRecent(recent: RecentPdf) {
        viewModelScope.launch { open(recent.pdfKey, recent.title, recent.lastPage) }
    }

    fun switch(id: String) {
        val current = state.value
        if (id != current.activeTabId && current.tabs.any { it.tabId == id }) select(current.tabs, id)
    }

    fun close(id: String) {
        val current = state.value
        val index = current.tabs.indexOfFirst { it.tabId == id }
        if (index < 0) return
        horizontalOffsets.remove(id)
        val tabs = current.tabs.filterNot { it.tabId == id }
        if (id == current.activeTabId) select(tabs, tabs.getOrNull(index)?.tabId ?: tabs.lastOrNull()?.tabId)
        else state.value = current.copy(tabs = tabs)
    }

    fun updatePosition(id: String, page: Int, offset: Int) {
        val current = state.value
        val tab = current.activeTab ?: return
        val count = current.document?.pageCount ?: return
        if (id != tab.tabId || page !in 0 until count) return
        if (tab.page != page) recordProgress(tab.pdfKey, count, page)
        updateActive(id) { it.copy(page = page, scrollOffsetPx = offset.coerceAtLeast(0)) }
    }

    fun horizontalOffset(id: String): Float = horizontalOffsets[id] ?: 0f

    fun updateZoom(id: String, zoom: Float, horizontalOffset: Float) {
        if (id != state.value.activeTabId) return
        horizontalOffsets[id] = horizontalOffset
        updateActive(id) { it.copy(zoom = zoom.coerceIn(1f, 5f)) }
    }

    fun toggleViewMode(id: String) = updateActive(id) {
        val mode = when (it.viewMode) {
            ReaderViewMode.CONTINUOUS -> ReaderViewMode.SINGLE_PAGE
            ReaderViewMode.SINGLE_PAGE -> ReaderViewMode.CONTINUOUS
        }
        it.copy(viewMode = mode, scrollOffsetPx = 0)
    }

    fun toggleNightMode(id: String) = updateActive(id) { it.copy(nightMode = !it.nightMode) }

    fun dismissNotice() {
        state.value = state.value.copy(notice = null)
    }

    private fun select(tabs: List<TabState>, activeId: String?, restoring: Boolean = state.value.restoring) {
        state.value = state.value.copy(
            restoring = restoring,
            selectionRevision = state.value.selectionRevision + 1,
            tabs = tabs,
            activeTabId = activeId,
            document = null,
            openError = null,
        )
    }

    private fun recordProgress(key: PdfKey, count: Int, page: Int) {
        pendingProgress.value = (pendingProgress.value - key) + (key to ReadingProgress(count, page))
    }

    private fun updateActive(id: String, transform: (TabState) -> TabState) {
        val current = state.value
        if (id == current.activeTabId) {
            state.value = current.copy(tabs = current.tabs.map { if (it.tabId == id) transform(it) else it })
        }
    }

    private suspend fun <T> attempt(error: ReaderError, block: suspend () -> T): T? = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        state.value = state.value.copy(notice = error)
        null
    }
}
