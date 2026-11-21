package dev.turjo.paperless.feature.editor

import android.graphics.Bitmap
import androidx.lifecycle.SavedStateHandle
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
import dev.turjo.paperless.core.model.splitGroups
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

internal data class ReviewGroup(val title: String, val kept: List<String>, val dropped: List<String> = emptyList())

internal data class ReviewState(
    val pages: List<ReviewPage> = emptyList(),
    val session: ScanSession? = null,
    val groups: List<ReviewGroup> = emptyList(),
    val draftTitle: String = "",
    val duplex: Boolean = false,
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
    private val savedState: SavedStateHandle,
) : ViewModel() {
    @AssistedInject
    constructor(
        @Assisted draftId: String,
        documents: DocumentRepository,
        sessions: ScanSessionRepository,
        processing: PageProcessing,
        files: FileStore,
        savedState: SavedStateHandle,
    ) : this(draftId, documents, sessions, processing, files::thumbFile, savedState = savedState)

    @AssistedFactory
    interface Factory {
        fun create(draftId: String): ReviewViewModel
    }

    private val mutable = MutableStateFlow(ReviewState(
        duplex = savedState["duplex"] ?: false,
        groups = List(savedState.get<Int>("groupCount") ?: 0) { index ->
            ReviewGroup(
                savedState.get<String>("title$index").orEmpty(),
                savedState.get<ArrayList<String>>("kept$index").orEmpty(),
                savedState.get<ArrayList<String>>("dropped$index").orEmpty(),
            )
        },
    ))
    val state = mutable.asStateFlow()
    private var committed: Pair<String, String>? = null
    private var pendingGroups: List<ReviewGroup>? = null
    private var nextGroupToSave = 1

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
                Triple(pages, session to targetTitle, document?.document?.title.orEmpty())
            }.catch {
                mutable.update { it.copy(loading = false, message = R.string.review_load_error) }
            }.collect { (pages, sessionAndTitle, draftTitle) ->
                val (session, targetTitle) = sessionAndTitle
                mutable.update { it.copy(
                    pages = pages, session = session, targetTitle = targetTitle, loading = false, draftTitle = draftTitle,
                ) }
                if (!state.value.finishPending) reconcileGroups(pages.map { it.page.id })
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

    private fun setGroups(groups: List<ReviewGroup>) {
        if (state.value.groups == groups) return
        groups.forEachIndexed { index, group ->
            savedState["title$index"] = group.title
            savedState["kept$index"] = ArrayList(group.kept)
            savedState["dropped$index"] = ArrayList(group.dropped)
        }
        for (index in groups.size until state.value.groups.size) {
            savedState.remove<String>("title$index")
            savedState.remove<ArrayList<String>>("kept$index")
            savedState.remove<ArrayList<String>>("dropped$index")
        }
        savedState["groupCount"] = groups.size
        mutable.update { it.copy(groups = groups) }
    }

    private fun reconcileGroups(ids: List<String>) {
        val groups = state.value.groups
        if (groups.isEmpty()) return
        val known = groups.flatMap { it.kept + it.dropped }.toSet()
        val added = ids.filterNot(known::contains)
        val existing = ids.toSet()
        val updated = groups.mapIndexed { index, group ->
            group.copy(
                kept = group.kept.filter(existing::contains) + if (index == groups.lastIndex) added else emptyList(),
                dropped = group.dropped.filter(existing::contains),
            )
        }
        // If the last kept page is manually deleted, retain any separators for review.
        setGroups(updated.map { group ->
            if (group.kept.isEmpty()) group.copy(kept = group.dropped, dropped = emptyList()) else group
        }.filter { it.kept.isNotEmpty() })
    }

    fun splitAtBlanks(duplex: Boolean) = change {
        savedState["duplex"] = duplex
        mutable.update { it.copy(duplex = duplex) }
        val ids = state.value.pages.map { it.page.id }
        val info = processing.blankInfo(ids)
        val groups = splitGroups(info, duplex)
        if (groups.isEmpty()) {
            message(R.string.review_no_split)
            return@change
        }
        val assigned = groups.flatMap { it.pageIds + it.droppedSeparatorIds }.toSet()
        val leading = ids.takeWhile { it !in assigned }
        val trailing = ids.takeLastWhile { it !in assigned }
        setGroups(groups.mapIndexed { index, group ->
            // The splitter skips edge blanks. Keep them because it did not identify them as separators.
            val kept = buildList {
                if (index == 0) addAll(leading)
                addAll(group.pageIds)
                if (index == groups.lastIndex) addAll(trailing)
            }
            ReviewGroup(groupTitle(index), kept, group.droppedSeparatorIds)
        })
    }

    fun renameGroup(index: Int, title: String) = editGroups {
        it[index] = it[index].copy(title = title)
    }

    fun restoreSeparator(id: String) = editGroups { groups ->
        val index = groups.indexOfFirst { id in it.dropped }
        if (index >= 0) {
            val group = groups[index]
            val positions = state.value.pages.mapIndexed { i, page -> page.page.id to i }.toMap()
            val position = positions.getValue(id)
            val at = group.kept.indexOfFirst { positions.getValue(it) > position }.let {
                if (it < 0) group.kept.size else it
            }
            groups[index] = group.copy(
                kept = group.kept.toMutableList().apply { add(at, id) },
                dropped = group.dropped - id,
            )
        }
    }

    fun splitHere(id: String) = editGroups { groups ->
        val index = groups.indexOfFirst { id in it.kept }
        if (index < 0) return@editGroups
        val group = groups[index]
        val at = group.kept.indexOf(id)
        if (at == 0) return@editGroups
        val ids = state.value.pages.map { it.page.id }
        val (before, after) = group.dropped.partition { ids.indexOf(it) < ids.indexOf(id) }
        val titleIndex = generateSequence(groups.size) { it + 1 }.first { number ->
            groups.none { it.title == groupTitle(number) }
        }
        groups[index] = group.copy(kept = group.kept.take(at), dropped = before)
        groups.add(index + 1, ReviewGroup(groupTitle(titleIndex), group.kept.drop(at), after))
    }

    fun join(index: Int) = editGroups { groups ->
        if (index !in 1..groups.lastIndex) return@editGroups
        val current = groups.removeAt(index)
        val previous = groups[index - 1]
        groups[index - 1] = previous.copy(
            kept = previous.kept + current.kept, dropped = previous.dropped + current.dropped,
        )
    }

    private fun groupTitle(index: Int) = "${state.value.draftTitle} (${index + 1})"

    private fun editGroups(edit: (MutableList<ReviewGroup>) -> Unit) {
        val current = state.value
        if (current.loading || current.changing || current.finishPending) return
        val groups = current.groups.ifEmpty {
            listOf(ReviewGroup(groupTitle(0), current.pages.map { it.page.id }))
        }.toMutableList()
        edit(groups)
        setGroups(groups)
    }

    fun reorder(ids: List<String>) = change {
        val current = state.value
        val groups = current.groups
        if (groups.isEmpty()) {
            documents.reorderPages(draftId, ids)
        } else {
            val reordered = groups.map { group -> group.copy(kept = ids.filter(group.kept::contains)) }
            // Keep separator positions in the draft so Restore can use the scan order.
            val kept = reordered.flatMap { it.kept }.iterator()
            val dropped = groups.flatMap { it.dropped }.toSet()
            documents.reorderPages(draftId, current.pages.map {
                it.page.id.takeIf(dropped::contains) ?: kept.next()
            })
            setGroups(reordered)
        }
    }

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
        val current = state.value
        val ids = if (current.groups.isEmpty()) current.pages.map { it.page.id } else current.groups.flatMap { it.kept }
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
                val groups = pendingGroups ?: state.value.groups
                if (groups.isNotEmpty()) {
                    val dropped = groups.flatMap { it.dropped }
                    if (pendingGroups == null && dropped.isNotEmpty()) {
                        val verified = processing.blankInfo(dropped).filter { it.isBlank }.map { it.id }.toSet()
                        if (!verified.containsAll(dropped)) {
                            message(R.string.review_separators_changed)
                            return@withContext
                        }
                    }
                    pendingGroups = groups
                    mutable.update { it.copy(finishPending = true) }
                    while (nextGroupToSave < groups.size) {
                        val group = groups[nextGroupToSave]
                        documents.splitOff(draftId, group.kept, group.title.trim())
                        nextGroupToSave++
                    }
                    dropped.forEach { documents.deletePage(it) }
                }
                val id = documents.commitDraft(
                    draftId, groups.firstOrNull()?.title?.trim() ?: title, session.targetDocumentId, session.insertAt,
                )
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
