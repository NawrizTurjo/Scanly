package dev.turjo.paperless.feature.editor

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.turjo.paperless.core.data.DocumentRepository
import dev.turjo.paperless.core.data.FileStore
import dev.turjo.paperless.core.data.PageProcessing
import dev.turjo.paperless.core.data.ScanSessionRepository
import dev.turjo.paperless.core.model.Page
import dev.turjo.paperless.core.model.PageFilter
import dev.turjo.paperless.core.model.ScanSession
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class ReviewPage(val page: Page, val thumbnail: File, val modified: Long, val busy: Boolean)

internal data class ReviewState(
    val pages: List<ReviewPage> = emptyList(),
    val session: ScanSession? = null,
    val targetTitle: String? = null,
    val loading: Boolean = true,
    val changing: Boolean = false,
    val savedId: String? = null,
    val discarded: Boolean = false,
    val finishPending: Boolean = false,
    val message: Int? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel(assistedFactory = ReviewViewModel.Factory::class)
internal class ReviewViewModel(
    private val draftId: String,
    private val documents: DocumentRepository,
    private val sessions: ScanSessionRepository,
    private val processing: PageProcessing,
    private val thumbnailFile: (String) -> File,
    private val thumbnailDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    @AssistedInject
    constructor(
        @Assisted draftId: String,
        documents: DocumentRepository,
        sessions: ScanSessionRepository,
        processing: PageProcessing,
        files: FileStore,
    ) : this(draftId, documents, sessions, processing, files::thumbFile)

    @AssistedFactory
    interface Factory {
        fun create(draftId: String): ReviewViewModel
    }

    private val mutable = MutableStateFlow(ReviewState())
    val state = mutable.asStateFlow()
    private var committed: Pair<String, String>? = null

    init {
        val sessionAndTarget = sessions.observeActive().flatMapLatest { current ->
            val session = current?.takeIf { it.draftDocumentId == draftId }
            val targetId = session?.targetDocumentId
            if (targetId == null) flowOf(session to null)
            else documents.observeDocument(targetId).map { target ->
                session to target?.document?.title
            }
        }
        viewModelScope.launch {
            combine(documents.observeDocument(draftId), processing.busyPageIds, sessionAndTarget) {
                document, busyIds, (session, targetTitle) ->
                val pages = withContext(thumbnailDispatcher) {
                    document?.pages.orEmpty().map { page ->
                        val thumbnail = thumbnailFile(page.id)
                        ReviewPage(page, thumbnail, thumbnail.lastModified(), page.id in busyIds)
                    }
                }
                Triple(pages, session, targetTitle)
            }.catch {
                mutable.update { it.copy(loading = false, message = R.string.review_load_error) }
            }.collect { (pages, session, targetTitle) ->
                mutable.update { it.copy(pages = pages, session = session, targetTitle = targetTitle, loading = false) }
            }
        }
    }

    fun message(resource: Int?) = mutable.update { it.copy(message = resource) }

    suspend fun presetPreview(page: Page, filter: PageFilter): Bitmap? = try {
        processing.previewBitmap(page.copy(filter = filter), 240)
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        message(R.string.editor_preview_error)
        null
    }

    fun reorder(ids: List<String>) = change { documents.reorderPages(draftId, ids) }

    fun rotate(id: String, delta: Int) = change {
        processing.awaitIdle(listOf(id))
        documents.rotatePage(id, delta)
        processing.enqueueRender(id)
    }

    fun delete(id: String) = change {
        processing.awaitIdle(listOf(id))
        documents.deletePage(id)
    }

    fun filterAll(filter: PageFilter) = change {
        val ids = state.value.pages.map { it.page.id }
        for (id in ids) {
            processing.awaitIdle(listOf(id))
            val page = documents.getPage(id) ?: continue
            documents.updatePage(page.copy(filter = filter))
            processing.enqueueRender(id)
        }
    }

    fun save(title: String) = change {
        withContext(NonCancellable) {
            val (documentId, sessionId) = committed ?: run {
                val session = requireNotNull(state.value.session)
                val id = documents.commitDraft(draftId, title, session.targetDocumentId, session.insertAt)
                (id to session.id).also {
                    committed = it
                    mutable.update { state -> state.copy(finishPending = true) }
                }
            }
            sessions.finish(sessionId)
            mutable.update { it.copy(savedId = documentId, finishPending = false) }
        }
    }

    fun discard() = change {
        val session = requireNotNull(state.value.session)
        processing.awaitIdle(state.value.pages.map { it.page.id })
        sessions.discard(session.id)
        mutable.update { it.copy(discarded = true) }
    }

    private fun change(block: suspend () -> Unit) {
        if (state.value.changing || state.value.savedId != null || state.value.discarded) return
        mutable.update { it.copy(changing = true) }
        viewModelScope.launch {
            try {
                block()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                message(R.string.review_change_error)
            } finally {
                mutable.update { it.copy(changing = false) }
            }
        }
    }
}
