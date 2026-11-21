package dev.turjo.paperless.feature.export

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.turjo.paperless.core.data.DocumentRepository
import dev.turjo.paperless.core.model.DocumentWithPages
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class ExportState(
    val loaded: Boolean = false,
    val document: DocumentWithPages? = null,
    val running: Boolean = false,
    val failed: Boolean = false,
    val saved: SavedExport? = null,
)

@HiltViewModel(assistedFactory = ExportViewModel.Factory::class)
internal class ExportViewModel @AssistedInject constructor(
    @Assisted documentId: String,
    documents: DocumentRepository,
    private val runner: ExportRunner,
    private val preferences: ExportPreferences,
) : ViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(documentId: String): ExportViewModel
    }

    private val mutableState = MutableStateFlow(ExportState())
    val state = mutableState.asStateFlow()
    val options = preferences.options
    private var operation: Job? = null

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val estimate = combine(state, options) { state, options -> state.document to options.quality }
        .distinctUntilChanged()
        .mapLatest { (document, quality) ->
            try {
                document?.let { runner.estimate(it, quality) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        viewModelScope.launch {
            documents.observeDocument(documentId).collect { document ->
                val available = document?.takeIf { it.document.deletedAt == null && it.pages.isNotEmpty() }
                mutableState.update { it.copy(loaded = true, document = available) }
            }
        }
    }

    fun select(value: ExportOptions) {
        preferences.select(value)
        mutableState.update { it.copy(saved = null) }
    }

    fun clearError() = mutableState.update { it.copy(failed = false) }

    fun openFailed() = mutableState.update { it.copy(failed = true) }

    fun cancel() {
        operation?.cancel()
    }

    fun dismiss() {
        cancel()
        mutableState.update { it.copy(saved = null, failed = false) }
    }

    fun export(name: String, share: ((List<File>, String) -> Unit)? = null) {
        if (mutableState.value.running) return
        val document = mutableState.value.document ?: return
        val selection = options.value
        mutableState.update { it.copy(running = true, failed = false, saved = null) }
        operation = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                if (share == null) {
                    val saved = runner.save(document, selection, name)
                    mutableState.update { it.copy(saved = saved) }
                } else {
                    runner.share(document, selection, name, share)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update { it.copy(failed = true) }
            } finally {
                mutableState.update { it.copy(running = false) }
            }
        }
    }
}
