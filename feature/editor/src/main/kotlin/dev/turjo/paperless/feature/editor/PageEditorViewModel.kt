package dev.turjo.paperless.feature.editor

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.turjo.paperless.core.data.DocumentRepository
import dev.turjo.paperless.core.data.PageProcessing
import dev.turjo.paperless.core.data.PixelSize
import dev.turjo.paperless.core.model.Page
import dev.turjo.paperless.core.model.PageFilter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class EditorState(
    val page: Page? = null,
    val edits: PageEdits? = null,
    val original: Bitmap? = null,
    val size: PixelSize? = null,
    val loading: Boolean = true,
    val dirty: Boolean = false,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val saving: Boolean = false,
    val detecting: Boolean = false,
    val saved: Boolean = false,
    val message: Int? = null,
)

@HiltViewModel(assistedFactory = PageEditorViewModel.Factory::class)
internal class PageEditorViewModel @AssistedInject constructor(
    @Assisted private val pageId: String,
    private val documents: DocumentRepository,
    private val processing: PageProcessing,
) : ViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(pageId: String): PageEditorViewModel
    }

    private val mutable = MutableStateFlow(EditorState())
    val state = mutable.asStateFlow()
    private val undo = ArrayDeque<PageEdits>()
    private val redo = ArrayDeque<PageEdits>()
    private var gestureStart: PageEdits? = null

    init {
        viewModelScope.launch {
            try {
                processing.awaitIdle(listOf(pageId))
                val page = requireNotNull(documents.getPage(pageId))
                mutable.update { it.copy(page = page, edits = PageEdits(page)) }
                val size = requireNotNull(processing.uprightSize(pageId))
                val original = processing.originalBitmap(pageId, 2048)
                mutable.update { it.copy(original = original, size = size) }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                message(R.string.editor_load_error)
            } finally {
                mutable.update { it.copy(loading = false) }
            }
        }
    }

    suspend fun preview(page: Page, maxSide: Int): Bitmap? = try {
        processing.previewBitmap(page, maxSide)
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        message(R.string.editor_preview_error)
        null
    }

    suspend fun skewAngle(page: Page): Double? = try {
        processing.skewAngle(page)
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }

    fun message(resource: Int?) = mutable.update { it.copy(message = resource) }

    fun beginGesture() {
        if (gestureStart == null && !state.value.saving && !state.value.detecting) {
            gestureStart = state.value.edits
        }
    }

    fun endGesture() {
        val start = gestureStart ?: return
        gestureStart = null
        val edits = state.value.edits ?: return
        if (edits != start) rememberUndo(start)
        publish(edits)
    }

    fun edit(transform: (PageEdits) -> PageEdits) {
        if (state.value.saving || state.value.detecting) return
        val current = state.value.edits ?: return
        val next = transform(current)
        if (next == current) return
        if (gestureStart == null) rememberUndo(current)
        publish(next)
    }

    fun undo() {
        if (state.value.saving || state.value.detecting) return
        endGesture()
        val previous = undo.removeLastOrNull() ?: return
        redo.addLast(requireNotNull(state.value.edits))
        publish(previous)
    }

    fun redo() {
        if (state.value.saving || state.value.detecting) return
        endGesture()
        val next = redo.removeLastOrNull() ?: return
        undo.addLast(requireNotNull(state.value.edits))
        publish(next)
    }

    fun autoCrop() {
        if (state.value.detecting || state.value.saving) return
        endGesture()
        mutable.update { it.copy(detecting = true) }
        viewModelScope.launch {
            try {
                val quad = processing.detectQuad(pageId)
                mutable.update { it.copy(detecting = false) }
                edit { it.copy(quad = quad) }
                if (quad == null) message(R.string.editor_no_edges)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                message(R.string.editor_detect_error)
            } finally {
                mutable.update { it.copy(detecting = false) }
            }
        }
    }

    fun done() {
        if (state.value.saving || state.value.saved || state.value.detecting) return
        val edits = state.value.edits ?: return
        endGesture()
        mutable.update { it.copy(saving = true) }
        viewModelScope.launch {
            try {
                val latest = requireNotNull(documents.getPage(pageId))
                documents.updatePage(edits.applyTo(latest))
                processing.enqueueRender(pageId)
                mutable.update { it.copy(saved = true, dirty = false) }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                message(R.string.editor_save_error)
            } finally {
                mutable.update { it.copy(saving = false) }
            }
        }
    }

    private fun rememberUndo(edits: PageEdits) {
        if (undo.size == 50) undo.removeFirst()
        undo.addLast(edits)
        redo.clear()
    }

    private fun publish(edits: PageEdits) = mutable.update {
        it.copy(
            edits = edits, dirty = it.page?.let(::PageEdits) != edits,
            canUndo = undo.isNotEmpty(), canRedo = redo.isNotEmpty(),
        )
    }
}

internal fun Page.originalComparison() = copy(
    filter = PageFilter.ORIGINAL, brightness = 0f, contrast = 0f, shadowFix = false, glareFix = false,
    deskew = false, inkRemoval = null,
)
