package dev.turjo.paperless.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.turjo.paperless.core.data.DocumentRepository
import dev.turjo.paperless.core.data.FolderRepository
import dev.turjo.paperless.core.model.DocumentSummary
import dev.turjo.paperless.core.model.LibraryFilter
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
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

internal sealed interface LibraryNotice {
    data class Deleted(val ids: Set<String>) : LibraryNotice
    data object Error : LibraryNotice
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
internal class LibraryViewModel @Inject constructor(
    private val documents: DocumentRepository,
    private val folders: FolderRepository,
) : ViewModel() {
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
    val folderList = folders.observeFolders()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val deleted = documents.observeDeleted()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        change { documents.purgeDeletedOlderThan(RECYCLE_BIN_DAYS) }
    }

    fun updateFilter(transform: (LibraryFilter) -> LibraryFilter) = mutableFilter.update(transform)

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

    fun deleteSelected() {
        val ids = selection.value.keys
        if (ids.isEmpty()) return
        change {
            documents.softDelete(ids)
            removeSelection(ids)
            pendingNotices.send(LibraryNotice.Deleted(ids))
        }
    }

    fun undoDelete(ids: Set<String>) = change { documents.restore(ids) }

    fun moveSelected(folderId: String?) {
        val ids = selection.value.keys
        change {
            folders.moveDocuments(ids, folderId)
            removeSelection(ids)
        }
    }

    fun moveSelectedToNewFolder(name: String) {
        val ids = selection.value.keys
        change {
            folders.moveDocumentsToNewFolder(ids, name)
            removeSelection(ids)
        }
    }

    fun createFolder(name: String, parentId: String?) = change { folders.create(name, parentId) }
    fun renameFolder(id: String, name: String) = change { folders.rename(id, name) }
    fun deleteFolder(id: String) = change { folders.delete(id) }
    fun restore(id: String) = change { documents.restore(id) }
    fun deleteForever(ids: List<String>) = change { ids.forEach { documents.deleteForever(it) } }

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
