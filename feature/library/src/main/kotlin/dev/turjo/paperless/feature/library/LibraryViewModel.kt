package dev.turjo.paperless.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.turjo.paperless.core.data.DocumentRepository
import dev.turjo.paperless.core.model.DocumentSummary
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class LibraryUiState(
    val documents: List<DocumentSummary> = emptyList(),
    val isLoading: Boolean = true,
)

@HiltViewModel
class LibraryViewModel @Inject constructor(repository: DocumentRepository) : ViewModel() {
    val uiState: StateFlow<LibraryUiState> = repository.observeLibrary()
        .map { LibraryUiState(documents = it, isLoading = false) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())
}
