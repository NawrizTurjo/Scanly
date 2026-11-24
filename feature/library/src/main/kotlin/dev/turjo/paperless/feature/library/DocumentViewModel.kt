package dev.turjo.paperless.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.Lazy
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.turjo.paperless.core.data.DocumentRepository
import dev.turjo.paperless.core.data.FileStore
import dev.turjo.paperless.core.data.FolderRepository
import dev.turjo.paperless.core.data.PageProcessing
import dev.turjo.paperless.core.data.applyBatchEdits
import dev.turjo.paperless.core.model.DocumentWithPages
import dev.turjo.paperless.core.model.PageBatchEdits
import dev.turjo.paperless.feature.export.ExportRunner
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class DocumentState(
    val loaded: Boolean = false,
    val document: DocumentWithPages? = null,
    val error: Boolean = false,
    val sharing: Boolean = false,
    val applyingBatch: Boolean = false,
    val savingOrder: Boolean = false,
    val orderRevision: Int = 0,
    val duplicationCount: Int = 0,
)

@HiltViewModel(assistedFactory = DocumentViewModel.Factory::class)
internal class DocumentViewModel @AssistedInject constructor(
    @Assisted private val id: String,
    private val documents: DocumentRepository,
    private val processing: PageProcessing,
    private val files: Lazy<FileStore>,
    private val folders: FolderRepository,
    private val exports: Lazy<ExportRunner>,
) : ViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(id: String): DocumentViewModel
    }

    private val mutableState = MutableStateFlow(DocumentState())
    val state = mutableState.asStateFlow()
    val folderList = folders.observeFolders()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val tags = documents.observeTags()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val documentTags = documents.observeDocumentTags(id)
    val busyPageIds = processing.busyPageIds
    private val mutations = Mutex()

    init {
        viewModelScope.launch {
            documents.observeDocument(id).collect { document ->
                val available = document?.takeIf { it.document.deletedAt == null && it.pages.isNotEmpty() }
                mutableState.update { it.copy(loaded = true, document = available) }
            }
        }
    }

    fun clearError() = mutableState.update { it.copy(error = false) }

    fun rename(title: String) = change {
        val trimmed = title.trim()
        if (trimmed.isNotEmpty()) documents.rename(id, trimmed)
    }

    fun favorite() = change {
        val document = documents.observeDocument(id).first()?.document ?: return@change
        documents.setFavorite(id, !document.isFavorite)
    }

    fun reorder(orderedIds: List<String>) {
        if (state.value.savingOrder) return
        mutableState.update { it.copy(savingOrder = true) }
        reportFailure {
            try {
                mutations.withLock {
                    documents.reorderPages(id, orderedIds)
                    documents.observeDocument(id).first { document ->
                        document == null || document.document.deletedAt != null ||
                            document.pages.map { it.id } == orderedIds ||
                            document.pages.map { it.id }.toSet() != orderedIds.toSet()
                    }
                }
            } finally {
                mutableState.update { it.copy(savingOrder = false, orderRevision = it.orderRevision + 1) }
            }
        }
    }

    fun applyToAllPages(edits: PageBatchEdits) {
        if (state.value.applyingBatch) return
        mutableState.update { it.copy(applyingBatch = true) }
        reportFailure {
            try {
                mutations.withLock {
                    val pages = documents.observeDocument(id).first()?.pages.orEmpty()
                    processing.applyBatchEdits(documents, pages.map { it.id }, edits)
                }
            } finally {
                mutableState.update { it.copy(applyingBatch = false) }
            }
        }
    }

    fun rotate(pageId: String, delta: Int) = change {
        processing.awaitIdle(listOf(pageId))
        val page = documents.getPage(pageId) ?: return@change
        documents.updatePage(page.copy(rotation = Math.floorMod(page.rotation + delta, 360)))
        processing.enqueueRender(pageId)
    }

    fun deletePage(pageId: String, confirmedLastPage: Boolean = false) = change {
        val document = documents.observeDocument(id).first() ?: return@change
        if (document.pages.none { it.id == pageId }) return@change
        if (document.pages.size > 1) {
            processing.awaitIdle(listOf(pageId))
            documents.deletePage(pageId)
        } else if (confirmedLastPage) {
            documents.softDelete(id)
        } else {
            mutableState.update { it.copy(error = true) }
        }
    }

    fun duplicate() = change {
        val pageIds = state.value.document?.pages?.map { it.id }.orEmpty()
        processing.awaitIdle(pageIds)
        documents.duplicate(id)
        mutableState.update { it.copy(duplicationCount = it.duplicationCount + 1) }
    }

    fun moveToFolder(folderId: String?) = change { folders.moveDocuments(setOf(id), folderId) }

    fun moveToNewFolder(name: String) = change {
        folders.moveDocumentsToNewFolder(setOf(id), name)
    }

    fun setTags(tagIds: Set<String>, newName: String?) = change {
        documents.setDocumentTags(id, tagIds, newName)
    }

    fun deleteDocument() = change { documents.softDelete(id) }

    fun share(launch: (List<File>, String) -> Unit) {
        val document = state.value.document ?: return
        if (state.value.sharing) return
        mutableState.update { it.copy(sharing = true) }
        reportFailure {
            try {
                exports.get().quickShare(document, launch)
            } finally {
                mutableState.update { it.copy(sharing = false) }
            }
        }
    }

    suspend fun thumbnail(pageId: String): Pair<File, Long> = withContext(Dispatchers.IO) {
        val file = files.get().thumbFile(pageId)
        file to file.lastModified()
    }

    private fun change(block: suspend () -> Unit) = reportFailure {
        mutations.withLock { block() }
    }

    private fun reportFailure(block: suspend () -> Unit) = viewModelScope.launch {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            mutableState.update { it.copy(error = true) }
        }
    }
}

internal fun insertAfterPage(pageNumber: Int, count: Int): Int? =
    pageNumber.takeIf { it in 1..count }
