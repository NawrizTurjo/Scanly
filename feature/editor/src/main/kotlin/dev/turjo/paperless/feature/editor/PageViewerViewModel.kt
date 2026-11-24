package dev.turjo.paperless.feature.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.turjo.paperless.core.data.isIdComposite
import dev.turjo.paperless.core.data.DocumentRepository
import dev.turjo.paperless.core.data.PageProcessing
import dev.turjo.paperless.core.data.ScanPageText
import dev.turjo.paperless.core.model.Page
import dev.turjo.paperless.core.model.paramsHash
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class ViewerPage(val page: Page, val busy: Boolean)

internal data class PageViewerState(
    val pages: List<ViewerPage> = emptyList(),
    val selectedIndex: Int = 0,
    val close: Boolean = false,
    val changing: Boolean = false,
    val message: Int? = null,
    /** A rendered page copy waiting for the share chooser; the screen launches it once and clears it. */
    val shareFile: File? = null,
)

@HiltViewModel(assistedFactory = PageViewerViewModel.Factory::class)
internal class PageViewerViewModel @AssistedInject constructor(
    @Assisted("document") private val documentId: String,
    @Assisted("page") startPageId: String,
    private val documents: DocumentRepository,
    private val processing: PageProcessing,
    private val sink: PageImageSink,
    private val scanText: ScanPageText,
) : ViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(@Assisted("document") documentId: String, @Assisted("page") startPageId: String): PageViewerViewModel
    }

    private val mutableState = MutableStateFlow(PageViewerState())
    val state = mutableState.asStateFlow()
    val automaticText = scanText.automatic

    fun observeText(pageId: String) = scanText.observe(pageId)

    suspend fun recognizeText(pageId: String): Boolean {
        processing.awaitIdle(listOf(pageId))
        val page = documents.getPage(pageId) ?: return false
        if (page.processedPath == null || page.processedHash != page.paramsHash()) {
            processing.enqueueRender(pageId)
            processing.awaitIdle(listOf(pageId))
        }
        return scanText.recognize(pageId) != null
    }

    init {
        viewModelScope.launch {
            combine(documents.observeDocument(documentId), processing.busyPageIds) { document, busyIds ->
                document?.takeIf { it.document.deletedAt == null }?.pages.orEmpty()
                    .map { ViewerPage(it, it.id in busyIds) }
            }.collect { pages ->
                mutableState.update { current ->
                    val selectedId = current.pages.getOrNull(current.selectedIndex)?.page?.id ?: startPageId
                    val index = pages.indexOfFirst { it.page.id == selectedId }
                    val lastIndex = pages.lastIndex.coerceAtLeast(0)
                    current.copy(
                        pages = pages,
                        selectedIndex = if (index >= 0) index else current.selectedIndex.coerceAtMost(lastIndex),
                        close = pages.isEmpty(),
                    )
                }
            }
        }
    }

    fun select(pageId: String) {
        mutableState.update { current ->
            val index = current.pages.indexOfFirst { it.page.id == pageId }
            if (index >= 0) current.copy(selectedIndex = index) else current
        }
    }

    fun rotate(pageId: String) = change(pageId) {
        if (documents.getPage(pageId)?.isIdComposite != false) return@change
        documents.rotatePage(pageId, 90)
        processing.enqueueRender(pageId)
    }

    fun delete(pageId: String) = change(pageId) {
        select(pageId)
        val document = documents.observeDocument(documentId).first()
        // A saved document never stays empty: removing its last page bins the document, as the detail screen does.
        if (document != null && !document.document.isDraft && document.pages.size == 1) {
            documents.softDelete(documentId)
            mutableState.update { it.copy(close = true) }
            return@change
        }
        documents.deletePage(pageId)
        state.first { current -> current.pages.none { it.page.id == pageId } }
    }

    /** Shares or saves the current page's enhanced image (the same file PDF export uses), named after the document. */
    fun exportPage(pageId: String, share: Boolean) {
        val current = state.value
        if (current.changing) return
        val index = current.pages.indexOfFirst { it.page.id == pageId }
        if (index < 0) return
        mutableState.update { it.copy(changing = true) }
        viewModelScope.launch {
            try {
                processing.awaitIdle(listOf(pageId))
                val page = requireNotNull(documents.getPage(pageId))
                if (page.processedHash != page.paramsHash()) {
                    processing.enqueueRender(pageId)
                    processing.awaitIdle(listOf(pageId))
                }
                val rendered = requireNotNull(documents.getPage(pageId)?.processedPath?.let(::File)?.takeIf(File::isFile))
                val title = documents.observeDocument(documentId).first()?.document?.title.orEmpty()
                val name = pageFileName(title, index + 1, current.pages.size)
                if (share) {
                    val copy = sink.shareCopy(rendered, name)
                    mutableState.update { it.copy(shareFile = copy) }
                } else {
                    sink.saveToPictures(rendered, name)
                    mutableState.update { it.copy(message = R.string.viewer_saved_image) }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                mutableState.update { it.copy(message = R.string.viewer_export_failed) }
            } finally {
                mutableState.update { it.copy(changing = false) }
            }
        }
    }

    fun shareLaunched() {
        mutableState.update { it.copy(shareFile = null) }
    }

    fun clearMessage() {
        mutableState.update { it.copy(message = null) }
    }

    private fun change(pageId: String, action: suspend () -> Unit) {
        val current = state.value
        if (current.changing || current.pages.none { it.page.id == pageId }) return
        mutableState.update { it.copy(changing = true) }
        viewModelScope.launch {
            try {
                processing.awaitIdle(listOf(pageId))
                action()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                mutableState.update { it.copy(message = R.string.viewer_change_failed) }
            } finally {
                mutableState.update { it.copy(changing = false) }
            }
        }
    }
}


/** "Biology notes p3.jpg"; a single-page document drops the page number. Unsafe file-name characters become "_". */
internal fun pageFileName(title: String, number: Int, count: Int): String {
    val clean = title.map { if (it.isISOControl() || it in "<>:\"/\\|?*") '_' else it }.joinToString("")
        .trim().trimEnd('.').take(60).ifBlank { "Scan" }
    return if (count > 1) "$clean p$number.jpg" else "$clean.jpg"
}
