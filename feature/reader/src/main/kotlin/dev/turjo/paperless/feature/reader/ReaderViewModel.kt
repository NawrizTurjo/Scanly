package dev.turjo.paperless.feature.reader

import android.net.Uri
import android.graphics.PointF
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import dev.turjo.paperless.core.data.AnnotationRepository
import dev.turjo.paperless.core.model.Annotation
import dev.turjo.paperless.core.model.AnnotationType
import dev.turjo.paperless.core.model.SignaturePlacement
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.mutableStateMapOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
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
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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
    private val outlineFiles: ReaderOutlineFiles? = null,
    private val annotationRepository: AnnotationRepository? = null,
    private val annotationFiles: ReaderAnnotationFiles? = null,
) : ViewModel() {
    private val state = MutableStateFlow(ReaderUiState())
    val uiState = state.asStateFlow()
    private val pendingStartTools = mutableStateMapOf<String, PdfStartTool>()
    internal val annotationLayers = mutableStateMapOf<String, AnnotationLayer>()
    internal var annotationPrompt by mutableStateOf<AnnotationPrompt?>(null)
        private set
    private val layerObservers = mutableMapOf<String, Job>()
    private val layerGestures = mutableMapOf<String, Job>()

    internal val searches = mutableStateMapOf<String, SearchState>()
    internal val crops = mutableStateMapOf<String, CropMode>()
    internal val drafts = mutableStateMapOf<String, OutlineDraft>()
    internal val secondaryPositions = mutableStateMapOf<String, TabState>()
    internal val secondaryPans = mutableMapOf<String, Float>()
    private val horizontalOffsets = mutableMapOf<String, Float>()
    private class ReadingProgress(val count: Int, val page: Int)

    private val pendingProgress = MutableStateFlow<Map<PdfKey, ReadingProgress>>(emptyMap())
    private val recentLoaded = CompletableDeferred<Unit>()
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
                repository.observeRecent().collect {
                    state.value = state.value.copy(recent = it)
                    recentLoaded.complete(Unit)
                }
            }
            recentLoaded.complete(Unit)
        }
        viewModelScope.launch {
            state.map { current ->
                current.activeTab?.let { Triple(current.selectionRevision, it.tabId, it.pdfKey) }
            }.distinctUntilChanged().collectLatest { selected ->
                if (selected == null) return@collectLatest
                val (revision, id, key) = selected
                try {
                    observeAnnotations(id, key)
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

    suspend fun open(
        key: PdfKey,
        title: String? = null,
        page: Int? = null,
        startTool: PdfStartTool? = null,
    ) {
        state.first { !it.restoring }
        if (page == null) recentLoaded.await()
        val current = state.value
        val existing = current.tabs.firstOrNull { it.pdfKey == key }
        if (existing != null) {
            if (startTool != null) pendingStartTools[existing.tabId] = startTool
            switch(existing.tabId)
            return
        }
        val recent = current.recent.firstOrNull { it.pdfKey == key }
        val tab = TabState(UUID.randomUUID().toString(), key, title ?: recent?.title ?: "PDF", page ?: recent?.lastPage ?: 0)
        if (startTool != null) pendingStartTools[tab.tabId] = startTool
        select(current.tabs + tab, tab.tabId)
    }

    fun openExternal(uri: Uri, title: String) {
        viewModelScope.launch {
            val key = attempt(ReaderError.OPEN) { repository.registerExternal(uri, title) } ?: return@launch
            open(key, title)
        }
    }

    internal suspend fun readOutline(key: PdfKey) = readerResult { requireNotNull(outlineFiles).read(key) }

    internal suspend fun suggestOutline(key: PdfKey) = readerResult { requireNotNull(outlineFiles).suggest(key) }

    internal suspend fun saveOutline(id: String, nodes: List<OutlineNode>, overwrite: Boolean): Boolean {
        val tab = state.value.tabs.firstOrNull { it.tabId == id } ?: return false
        val result = readerResult { requireNotNull(outlineFiles).save(tab.pdfKey, nodes, overwrite) }
        if (result !is SaveResult.Saved) return false
        val title = if (overwrite) tab.title else tab.title.substringBeforeLast('.') + " (edited).pdf"
        val key = if (overwrite) tab.pdfKey else readerResult {
            repository.registerExternal(result.uri, title)
        } ?: return false
        drafts.remove(id)
        if (key != tab.pdfKey && annotationLayers[id]?.dirty == true) {
            open(key, title, tab.page)
            return true
        }
        val current = state.value
        val updated = current.tabs.map { if (it.tabId == id) it.copy(pdfKey = key, title = title) else it }
        if (key != tab.pdfKey) releaseAnnotations(id)
        searches[id]?.let { searches[id] = SearchState(it.query, status = if (it.query.isBlank()) null else SearchStatus.PENDING) }
        secondaryPositions.remove(id)
        if (current.activeTabId == id) select(updated, id) else state.value = current.copy(tabs = updated)
        return true
    }

    internal suspend fun openPane(key: PdfKey) = readerResult { sessions.open(key) }

    internal fun releasePane(key: PdfKey) = sessions.release(key)

    internal fun updateOtherPosition(id: String, count: Int, page: Int, offset: Int) {
        val current = state.value
        val tab = current.tabs.firstOrNull { it.tabId == id } ?: return
        if (page !in 0 until count || id == current.activeTabId) return
        if (tab.page != page) recordProgress(tab.pdfKey, count, page)
        state.value = current.copy(tabs = current.tabs.map {
            if (it.tabId == id) it.copy(page = page, scrollOffsetPx = offset.coerceAtLeast(0)) else it
        })
    }

    fun openRecent(recent: RecentPdf) {
        viewModelScope.launch { open(recent.pdfKey, recent.title, recent.lastPage) }
    }

    fun switch(id: String) {
        val current = state.value
        if (id != current.activeTabId && current.tabs.any { it.tabId == id }) select(current.tabs, id)
    }

    fun close(id: String) {
        val pending = layerGestures[id]
        if (pending?.isActive == true) {
            viewModelScope.launch { pending.join(); close(id) }
            return
        }
        val layer = annotationLayers[id]
        if (layer?.saving == true) return
        if (layer?.dirty == true) {
            annotationPrompt = AnnotationPrompt(id, closing = true)
        } else {
            closeTab(id)
        }
    }

    private fun closeTab(id: String) {
        val current = state.value
        val index = current.tabs.indexOfFirst { it.tabId == id }
        if (index < 0) return
        releaseAnnotations(id)
        searches.remove(id)
        crops.remove(id)
        drafts.remove(id)
        secondaryPositions.remove(id)
        secondaryPans.remove(id)
        horizontalOffsets.remove(id)
        val tabs = current.tabs.filterNot { it.tabId == id }
        if (id == current.activeTabId) select(tabs, tabs.getOrNull(index)?.tabId ?: tabs.lastOrNull()?.tabId)
        else state.value = current.copy(tabs = tabs)
    }

    internal fun observeAnnotations(id: String, key: PdfKey) {
        if (id in layerObservers || annotationRepository == null) return
        layerObservers[id] = viewModelScope.launch {
            attempt(ReaderError.OPEN) {
                annotationRepository.observe(key).collect { saved ->
                    val layer = annotationLayers[id]
                    if (layer == null) {
                        annotationLayers[id] = AnnotationLayer(saved)
                    } else if (!layer.dirty && !layer.saving && layer.saved != saved) {
                        annotationLayers[id] = layer.copy(saved = saved, marks = saved, undo = emptyList(), redo = emptyList())
                    }
                }
            }
        }
    }

    private fun releaseAnnotations(id: String) {
        layerObservers.remove(id)?.cancel()
        layerGestures.remove(id)?.cancel()
        annotationLayers.remove(id)
        pendingStartTools.remove(id)
    }

    internal fun startAnnotations(id: String) {
        val current = state.value
        if (current.activeTabId != id || current.document == null) return
        val layer = annotationLayers[id]?.takeUnless { it.saving } ?: return
        when (pendingStartTools.remove(id)) {
            PdfStartTool.SIGNATURE -> annotationLayers[id] = layer.copy(
                editing = true,
                tool = AnnotationTool.SIGNATURE,
                pendingSignature = null,
                signaturePicker = SignaturePickerMode.DRAW_IF_EMPTY,
            )
            null -> Unit
        }
    }

    internal fun pendingStartTool(id: String): PdfStartTool? = pendingStartTools[id]

    internal fun editAnnotations(id: String, update: (AnnotationLayer) -> AnnotationLayer) {
        val layer = annotationLayers[id] ?: return
        if (!layer.saving) annotationLayers[id] = update(layer)
    }

    internal fun finishAnnotations(id: String) {
        viewModelScope.launch {
            layerGestures[id]?.join()
            if (annotationLayers[id]?.saving == false) annotationPrompt = AnnotationPrompt(id, closing = false)
        }
    }

    internal fun dismissAnnotationPrompt() {
        if (annotationPrompt?.let { annotationLayers[it.tabId]?.saving } != true) annotationPrompt = null
    }

    internal fun discardAnnotations(prompt: AnnotationPrompt) {
        editAnnotations(prompt.tabId, AnnotationLayer::discard)
        annotationPrompt = null
        if (prompt.closing) closeTab(prompt.tabId)
    }

    internal fun saveAnnotations(prompt: AnnotationPrompt, flatten: Boolean) {
        val id = prompt.tabId
        val tab = state.value.tabs.firstOrNull { it.tabId == id } ?: return
        val layer = annotationLayers[id] ?: return
        if (layer.saving) return
        annotationLayers[id] = layer.copy(saving = true)
        viewModelScope.launch {
            val success = attempt(ReaderError.SAVE) {
                if (flatten) {
                    val result = requireNotNull(annotationFiles).save(tab.pdfKey, layer.marks)
                    if (result !is SaveResult.Saved) error("Could not save an annotated PDF")
                    val title = tab.title.substringBeforeLast('.') + " (edited).pdf"
                    val key = repository.registerExternal(result.uri, title)
                    open(key, title, tab.page)
                } else {
                    requireNotNull(annotationRepository).saveBatch(tab.pdfKey, layer.marks)
                }
                true
            } == true
            annotationLayers[id] = if (!success) layer else if (flatten) {
                layer.discard()
            } else {
                layer.copy(
                    saved = layer.marks, editing = false, undo = emptyList(), redo = emptyList(),
                    pendingSignature = null, signaturePicker = null,
                )
            }
            if (success) {
                annotationPrompt = null
                if (prompt.closing) closeTab(id)
            }
        }
    }

    internal fun annotate(
        id: String,
        document: PdfDocumentHandle,
        page: Int,
        tool: AnnotationTool,
        color: Int,
        width: Float,
        points: List<PointF>,
        note: String? = null,
    ) {
        val tab = state.value.activeTab?.takeIf { it.tabId == id } ?: return
        if (annotationLayers[id]?.let { it.editing && !it.saving } != true) return
        if (points.isEmpty() || annotationPrompt != null) return
        if (tool == AnnotationTool.ERASER) {
            launchGesture(id) {
                val marks = annotationLayers[id]?.marks.orEmpty()
                val hits = withContext(Dispatchers.Default) {
                    val samples = eraserSamples(points)
                    marks.filter { mark -> mark.page == page && samples.any { mark.touches(it, 5f) } }
                        .mapTo(hashSetOf()) { it.id }
                }
                editAnnotations(id) { it.change(it.marks.filterNot { mark -> mark.id in hits }) }
            }
            return
        }
        val type = tool.type ?: return
        val mark = Annotation(UUID.randomUUID().toString(), tab.pdfKey, page, type, color, width, points, note = note)
        if (type == AnnotationType.INK || type == AnnotationType.NOTE) {
            launchGesture(id) { editAnnotations(id) { it.change(it.marks + mark) } }
            return
        }
        launchGesture(id) {
            val first = points.first()
            val last = points.last()
            val alongText = kotlin.math.abs(last.x - first.x) >= 4f &&
                points.all { kotlin.math.abs(it.y - first.y) <= 8f }
            val selection = if (type != AnnotationType.HIGHLIGHT || alongText) {
                attempt(ReaderError.OPEN) { document.select(page, first, last) }?.second.orEmpty()
            } else emptyList()
            val rects = if (type == AnnotationType.HIGHLIGHT) selection.filter {
                first.y in (it.top - 4f)..(it.bottom + 4f)
            } else selection
            when {
                rects.isNotEmpty() -> editAnnotations(id) { it.change(it.marks + mark.copy(points = emptyList(), rects = rects)) }
                type == AnnotationType.HIGHLIGHT -> editAnnotations(id) { it.change(it.marks + mark) }
            }
        }
    }

    internal fun changeSignature(
        tabId: String,
        page: Int,
        id: String?,
        placement: SignaturePlacement?,
    ) {
        val key = state.value.tabs.firstOrNull { it.tabId == tabId }?.pdfKey ?: return
        editAnnotations(tabId) { layer ->
            if (!layer.editing || layer.tool != AnnotationTool.SIGNATURE) return@editAnnotations layer
            val marks = if (id == null && placement != null) {
                layer.marks + Annotation(UUID.randomUUID().toString(), key, page, AnnotationType.SIGNATURE,
                    color = 0, width = 0f, signature = placement)
            } else {
                layer.marks.mapNotNull { mark ->
                    if (mark.id == id) placement?.let { mark.copy(signature = it) } else mark
                }
            }
            layer.change(marks).copy(pendingSignature = null)
        }
    }

    internal fun stepAnnotationHistory(id: String, redo: Boolean) {
        launchGesture(id) { editAnnotations(id) { if (redo) it.redo() else it.undo() } }
    }

    private fun launchGesture(id: String, action: suspend () -> Unit) {
        val previous = layerGestures[id]
        layerGestures[id] = viewModelScope.launch {
            previous?.join()
            action()
        }
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
