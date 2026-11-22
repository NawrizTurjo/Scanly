package dev.turjo.paperless.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.Lazy
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.turjo.paperless.core.data.DocumentRepository
import dev.turjo.paperless.core.data.FileStore
import dev.turjo.paperless.core.data.FolderRepository
import dev.turjo.paperless.core.data.PageProcessing
import dev.turjo.paperless.core.data.ReaderRepository
import dev.turjo.paperless.core.data.ScanSessionRepository
import dev.turjo.paperless.core.model.DocumentSummary
import dev.turjo.paperless.core.model.LibraryFilter
import dev.turjo.paperless.core.model.PdfKey
import dev.turjo.paperless.feature.export.ExportRunner
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal const val RECYCLE_BIN_DAYS = 30

internal data class LibraryUiState(
    val documents: List<DocumentSummary> = emptyList(),
    val isLoading: Boolean = true,
    val filter: LibraryFilter = LibraryFilter(),
)

internal data class UnsavedScan(
    val sessionId: String,
    val draftId: String,
    val startedAt: Long,
    val pageCount: Int,
    val thumbnailPageIds: List<String>,
)

internal data class ContinueReadingItem(
    val pdfKey: PdfKey,
    val title: String,
    val page: Int,
    val pageCount: Int,
) {
    val progress: Float get() = page.toFloat() / pageCount
}

internal sealed interface LibraryNotice {
    data class Deleted(val ids: Set<String>) : LibraryNotice
    data object Error : LibraryNotice
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
internal class LibraryViewModel(
    private val documents: DocumentRepository,
    private val folders: FolderRepository,
    reader: ReaderRepository,
    private val sessions: ScanSessionRepository,
    private val awaitIdle: suspend (Collection<String>) -> Unit,
    val thumbnailFile: (String) -> File,
    private val exports: Lazy<ExportRunner>,
) : ViewModel() {
    @Inject
    constructor(
        documents: DocumentRepository,
        folders: FolderRepository,
        reader: ReaderRepository,
        sessions: ScanSessionRepository,
        processing: PageProcessing,
        files: FileStore,
        exports: Lazy<ExportRunner>,
    ) : this(documents, folders, reader, sessions, processing::awaitIdle, files::thumbFile, exports)

    private val mutableFilter = MutableStateFlow(LibraryFilter())
    val filter = mutableFilter.asStateFlow()
    private val mutableSelection = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val selection = mutableSelection.asStateFlow()
    private val mutableListLayout = MutableStateFlow(false)
    val listLayout = mutableListLayout.asStateFlow()
    private val pendingNotices = Channel<LibraryNotice>(Channel.UNLIMITED)
    val notices = pendingNotices.receiveAsFlow()
    private val mutations = Mutex()

    val uiState = filter.flatMapLatest { active ->
        documents.observeLibrary(active).map {
            LibraryUiState(documents = it, isLoading = false, filter = active)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())
    val unsavedScan = sessions.observeActive().flatMapLatest { session ->
        if (session == null) flowOf(null)
        else documents.observeDocument(session.draftDocumentId).map { draft ->
            if (draft == null || !draft.document.isDraft || draft.pages.isEmpty()) null
            else UnsavedScan(
                session.id, session.draftDocumentId, session.startedAt, draft.pages.size,
                draft.pages.take(3).map { it.id },
            )
        }
    }.combine(filter) { scan, active -> scan.takeIf { active.query.isBlank() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val continueReading = reader.observeRecent().map { recent ->
        recent.asSequence().filter { it.pageCount > 0 }.take(5).map {
            ContinueReadingItem(it.pdfKey, it.title, it.lastPage.coerceIn(0, it.pageCount - 1) + 1, it.pageCount)
        }.toList()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val folderList = folders.observeFolders()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val tags = documents.observeTags()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val deleted = documents.observeDeleted()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        change { documents.purgeDeletedOlderThan(RECYCLE_BIN_DAYS) }
    }

    fun updateFilter(transform: (LibraryFilter) -> LibraryFilter) = mutableFilter.update(transform)

    fun toggleTag(id: String) = updateFilter { active ->
        active.copy(tagId = id.takeUnless { it == active.tagId })
    }

    fun documentTags(id: String) = documents.observeDocumentTags(id)

    fun setTags(id: String, tagIds: Set<String>, newName: String?) =
        change { documents.setDocumentTags(id, tagIds, newName) }

    fun renameTag(id: String, name: String) = change { documents.renameTag(id, name) }

    fun deleteTag(id: String) = change {
        documents.deleteTag(id)
        updateFilter { if (it.tagId == id) it.copy(tagId = null) else it }
    }

    fun share(id: String, launch: (List<File>, String) -> Unit) = change {
        val document = documents.observeDocument(id).first()
            ?.takeIf { it.document.deletedAt == null && it.pages.isNotEmpty() } ?: return@change
        exports.get().quickShare(document, launch)
    }

    fun toggleLayout() = mutableListLayout.update { !it }

    fun toggleSelection(document: DocumentSummary) = mutableSelection.update {
        if (document.id in it) it - document.id else it + (document.id to document.isFavorite)
    }

    fun clearSelection() = mutableSelection.update { emptyMap() }

    fun favoriteSelected() {
        val selected = selection.value
        change {
            documents.setFavorite(selected.keys, selected.values.any { !it })
            removeSelection(selected.keys)
        }
    }

    fun deleteSelected() = deleteDocuments(selection.value.keys)

    fun deleteDocument(id: String) = deleteDocuments(setOf(id))

    private fun deleteDocuments(ids: Set<String>) {
        if (ids.isEmpty()) return
        change {
            documents.softDelete(ids)
            removeSelection(ids)
            pendingNotices.send(LibraryNotice.Deleted(ids))
        }
    }

    fun undoDelete(ids: Set<String>) = change { documents.restore(ids) }

    fun moveDocuments(ids: Set<String>, folderId: String?) = change {
        folders.moveDocuments(ids, folderId)
        removeSelection(ids)
    }

    fun moveDocumentsToNewFolder(ids: Set<String>, name: String) = change {
        folders.moveDocumentsToNewFolder(ids, name)
        removeSelection(ids)
    }

    fun moveSelected(folderId: String?) = moveDocuments(selection.value.keys, folderId)

    fun moveSelectedToNewFolder(name: String) = moveDocumentsToNewFolder(selection.value.keys, name)

    fun createFolder(name: String, parentId: String?) = change { folders.create(name, parentId) }
    fun renameFolder(id: String, name: String) = change { folders.rename(id, name) }
    fun deleteFolder(id: String) = change { folders.delete(id) }
    fun restore(id: String) = change { documents.restore(id) }
    fun deleteForever(ids: List<String>) = change { ids.forEach { documents.deleteForever(it) } }

    fun discardScan(sessionId: String) = change {
        val session = sessions.active()?.takeIf { it.id == sessionId } ?: return@change
        val pages = documents.observeDocument(session.draftDocumentId).first()?.pages.orEmpty()
        awaitIdle(pages.map { it.id })
        sessions.discard(session.id)
    }

    private fun removeSelection(ids: Set<String>) = mutableSelection.update { it - ids }

    private fun change(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                mutations.withLock { block() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                pendingNotices.send(LibraryNotice.Error)
            }
        }
    }
}
