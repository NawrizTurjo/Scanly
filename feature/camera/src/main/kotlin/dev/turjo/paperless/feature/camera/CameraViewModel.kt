package dev.turjo.paperless.feature.camera

import android.graphics.BitmapFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.turjo.paperless.core.data.DocumentRepository
import dev.turjo.paperless.core.data.DuplexPhase
import dev.turjo.paperless.core.data.DuplexProgress
import dev.turjo.paperless.core.data.DuplexProgressStore
import dev.turjo.paperless.core.data.FileDuplexProgressStore
import dev.turjo.paperless.core.data.FileStore
import dev.turjo.paperless.core.data.PageProcessing
import dev.turjo.paperless.core.data.PixelSize
import dev.turjo.paperless.core.data.ScanSessionRepository
import dev.turjo.paperless.core.model.DocumentWithPages
import dev.turjo.paperless.core.model.PageFilter
import dev.turjo.paperless.core.model.PageMode
import dev.turjo.paperless.core.model.ScanSession
import dev.turjo.paperless.core.model.interleaveDuplex
import java.io.File
import java.util.Optional
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun interface CaptureController {
    suspend fun capture(destination: File)
}

internal data class CameraArgs(
    val targetDocumentId: String?,
    val insertAt: Int?,
    val singleMode: Boolean,
    val resumeDraftId: String? = null,
    /** "id_card", "book" or "whiteboard" to open in that mode (Home shortcuts); null keeps the last mode. */
    val startMode: String? = null,
)

internal data class PendingScan(val session: ScanSession, val pageCount: Int)

internal enum class ScanMode(val pageMode: PageMode) {
    DOCUMENT(PageMode.NORMAL),
    ID_CARD(PageMode.ID_FRONT),
    BOOK(PageMode.BOOK_L),
    WHITEBOARD(PageMode.WHITEBOARD),
}

internal const val MAX_ID_SIDES = 6

internal data class CameraState(
    val ready: Boolean = false,
    val singleMode: Boolean,
    val mode: ScanMode = ScanMode.DOCUMENT,
    val idSideIds: List<String> = emptyList(),
    val chooseIdSide: Boolean = false,
    val pageIds: List<String> = emptyList(),
    val capturing: Boolean = false,
    val importing: Boolean = false,
    val closing: Boolean = false,
    val confirmDiscard: Boolean = false,
    val pageToDelete: String? = null,
    val pendingScan: PendingScan? = null,
    val confirmDiscardPrevious: Boolean = false,
    val confirmDuplexMismatch: Boolean = false,
    val duplexPhase: DuplexPhase? = null,
    val frontCount: Int = 0,
    val backCount: Int = 0,
    val autoCapture: Boolean = false,
    val autoCaptureProgress: Float? = null,
) {
    val idBack get() = idSideIds.isNotEmpty()
    val canEdit get() = ready && !chooseIdSide && !capturing && !importing && !closing && !confirmDiscard && !confirmDuplexMismatch && pageToDelete == null
}

internal sealed interface CameraEvent {
    data class Review(val draftId: String) : CameraEvent
    data class Saved(val title: String) : CameraEvent
    data object Captured : CameraEvent
    data object Close : CameraEvent
    data object Error : CameraEvent
}

@HiltViewModel(assistedFactory = CameraViewModel.Factory::class)
internal class CameraViewModel internal constructor(
    private val args: CameraArgs,
    private val sessions: ScanSessionRepository,
    private val documents: DocumentRepository,
    private val processing: PageProcessing,
    private val newTempFile: () -> File,
    private val imageSize: (File) -> PixelSize,
    val thumbnailFile: (String) -> File,
    private val io: CoroutineDispatcher,
    private val duplexStore: DuplexProgressStore,
    val liveQuadSource: LiveQuadSource? = null,
) : ViewModel() {
    @AssistedInject
    constructor(
        @Assisted args: CameraArgs,
        sessions: ScanSessionRepository,
        documents: DocumentRepository,
        processing: PageProcessing,
        files: FileStore,
        liveQuadSource: Optional<LiveQuadSource>,
        duplexStore: FileDuplexProgressStore,
    ) : this(
        args, sessions, documents, processing, { files.newTempFile(".jpg") },
        ::readImageSize, files::thumbFile, Dispatchers.IO, duplexStore, liveQuadSource.orElse(null),
    )

    @AssistedFactory
    interface Factory {
        fun create(args: CameraArgs): CameraViewModel
    }

    val busyPageIds = processing.busyPageIds
    private val mutableState = MutableStateFlow(CameraState(singleMode = args.singleMode))
    val state = mutableState.asStateFlow()
    private val eventChannel = Channel<CameraEvent>(Channel.BUFFERED)
    val events = eventChannel.receiveAsFlow()
    private lateinit var session: ScanSession
    private var initializationFailed = false
    private val autoCapture = AutoCaptureController()
    private var latestQuad: OverlayQuad? = null
    private var duplex = DuplexProgress()
    private var waitForDerivedPages = false

    init {
        viewModelScope.launch {
            initializationFailed = !reportErrors {
                val active = sessions.active()
                if (active == null) {
                    openSession(sessions.start(args.targetDocumentId, args.insertAt))
                } else {
                    val draft = documents.observeDocument(active.draftDocumentId).first()
                    val pageCount = draft?.pages.orEmpty().size
                    val sameTarget = args.targetDocumentId != null && args.targetDocumentId == active.targetDocumentId
                    when {
                        args.resumeDraftId == active.draftDocumentId || (pageCount > 0 && sameTarget) ->
                            openSession(active, draft)
                        pageCount > 0 -> mutableState.update { it.copy(pendingScan = PendingScan(active, pageCount)) }
                        active.targetDocumentId == args.targetDocumentId && active.insertAt == args.insertAt ->
                            openSession(active, draft)
                        else -> replaceSession(saveExisting = true, expectedSessionId = active.id)
                    }
                }
            }
        }
    }

    fun continueScan() = resolvePendingScan { pending ->
        check(sessions.active()?.id == pending.session.id)
        openSession(pending.session)
    }

    fun requestDiscardPrevious(confirm: Boolean) {
        if (state.value.pendingScan != null && !state.value.closing) {
            mutableState.update { it.copy(confirmDiscardPrevious = confirm) }
        }
    }

    fun startFresh(saveExisting: Boolean) = resolvePendingScan { pending ->
        val pages = documents.observeDocument(pending.session.draftDocumentId).first()?.pages.orEmpty()
        if (!saveExisting) processing.awaitIdle(pages.map { it.id })
        replaceSession(saveExisting, pending.session.id)
    }

    private suspend fun replaceSession(saveExisting: Boolean, expectedSessionId: String) {
        val result = sessions.startFresh(saveExisting, args.targetDocumentId, args.insertAt, expectedSessionId)
        result.savedTitle?.let { eventChannel.send(CameraEvent.Saved(it)) }
        openSession(result.session)
    }

    private fun resolvePendingScan(action: suspend (PendingScan) -> Unit) {
        val pending = state.value.pendingScan ?: return
        if (state.value.closing) return
        mutableState.update { it.copy(closing = true, confirmDiscardPrevious = false) }
        viewModelScope.launch {
            try {
                reportErrors { action(pending) }
            } finally {
                mutableState.update { it.copy(closing = false) }
            }
        }
    }

    private suspend fun openSession(active: ScanSession) =
        openSession(active, documents.observeDocument(active.draftDocumentId).first())

    private suspend fun openSession(active: ScanSession, draft: DocumentWithPages?) {
        val progress = duplexStore.read(active.id)
        session = active
        duplex = progress
        val pages = draft?.pages.orEmpty()
        val trailingBacks = pages.takeLastWhile { it.mode == PageMode.ID_BACK }
        val front = pages.getOrNull(pages.lastIndex - trailingBacks.size)
        val idSides = if (!progress.enabled && front?.mode == PageMode.ID_FRONT &&
            documents.idCompositesFor(front).isEmpty()
        ) {
            listOf(front) + trailingBacks
        } else emptyList()
        idSides.filter { it.processedPath == null && it.id !in processing.busyPageIds.value }.forEach {
            processing.enqueueNewPage(it.id, it.mode, PageFilter.DOCUMENT)
        }
        val rememberedMode = lastSessionMode?.takeIf { it.first == active.id }?.second
        waitForDerivedPages = pages.any {
            it.mode == PageMode.BOOK_L || it.mode == PageMode.ID_FRONT || it.mode == PageMode.ID_BACK
        }
        mutableState.update {
            it.copy(
                pendingScan = null,
                ready = draft?.document?.isDraft == true,
                pageIds = pages.map { page -> page.id },
                mode = when {
                    progress.enabled -> ScanMode.DOCUMENT
                    idSides.isNotEmpty() -> ScanMode.ID_CARD
                    else -> args.startMode?.let(::scanModeFor) ?: rememberedMode ?: ScanMode.DOCUMENT
                },
                singleMode = it.singleMode && !progress.enabled,
                idSideIds = idSides.map { page -> page.id },
                chooseIdSide = idSides.size >= 2,
                duplexPhase = progress.phase.takeIf { progress.enabled },
            ).withSideCounts()
        }
        viewModelScope.launch {
            documents.observeDocument(active.draftDocumentId).collect { current ->
                val ids = current?.pages.orEmpty().map { it.id }
                mutableState.update {
                    it.copy(ready = current?.document?.isDraft == true, pageIds = ids).withSideCounts()
                }
            }
        }
    }

    fun setSingleMode(single: Boolean) {
        if (state.value.canEdit && state.value.duplexPhase == null && state.value.mode != ScanMode.ID_CARD) {
            mutableState.update { it.copy(singleMode = single) }
        }
    }

    fun setMode(mode: ScanMode) {
        val current = state.value
        if (!current.canEdit || current.idBack || current.duplexPhase != null || current.mode == mode) return
        lastSessionMode = session.id to mode
        autoCapture.stopTracking()
        mutableState.update { it.copy(mode = mode, autoCaptureProgress = null) }
    }

    fun toggleAutoCapture() {
        if (!state.value.canEdit) return
        autoCapture.stopTracking()
        mutableState.update { it.copy(autoCapture = !it.autoCapture, autoCaptureProgress = null) }
    }

    fun onOverlay(quad: OverlayQuad?, nowMillis: Long, controller: CaptureController?) {
        latestQuad = quad
        val current = state.value
        if (!current.autoCapture || !current.canEdit || controller == null) {
            autoCapture.stopTracking()
        } else if (autoCapture.update(quad, nowMillis) == AutoCaptureAction.CaptureNow) {
            capture(controller)
        }
        mutableState.update { it.copy(autoCaptureProgress = autoCapture.progress) }
    }

    fun toggleDuplex() {
        val current = state.value
        if (!current.canEdit || current.singleMode || current.mode != ScanMode.DOCUMENT) return
        changeDuplex(duplex.copy(enabled = !duplex.enabled))
    }

    fun scanBacks() {
        val current = state.value
        if (current.canEdit && current.duplexPhase == DuplexPhase.FRONTS && current.frontCount > 0) {
            changeDuplex(duplex.copy(phase = DuplexPhase.BACKS))
        }
    }

    private fun changeDuplex(progress: DuplexProgress) {
        mutableState.update { it.copy(closing = true) }
        viewModelScope.launch {
            try {
                reportErrors { updateDuplex(progress) }
            } finally {
                mutableState.update { it.copy(closing = false) }
            }
        }
    }

    private suspend fun updateDuplex(progress: DuplexProgress) {
        duplexStore.write(session.id, progress)
        duplex = progress
        mutableState.update { it.copy(duplexPhase = progress.phase.takeIf { progress.enabled }).withSideCounts() }
    }

    fun capture(controller: CaptureController) = capture(controller, finishId = false)

    fun captureAndFinishId(controller: CaptureController) = capture(controller, finishId = true)

    private fun capture(controller: CaptureController, finishId: Boolean) {
        if (!state.value.canEdit) return
        autoCapture.startCapture(latestQuad)
        // A quad the overlay held steady is where the page is; processing then skips the full search (~2 s/page).
        val hint = latestQuad?.takeIf { it.stableForMillis >= HINT_STABLE_MILLIS }?.points?.copyOf()
        mutableState.update { it.copy(capturing = true, autoCaptureProgress = null) }
        viewModelScope.launch {
            var captured = false
            try {
                captured = reportErrors {
                    val finished = addPage(hint) { file ->
                        controller.capture(file)
                        eventChannel.trySend(CameraEvent.Captured)
                    }
                    if (finishId && state.value.chooseIdSide) completeId()
                    else if (finished) reviewWhenReady()
                }
            } finally {
                autoCapture.captureFinished(captured)
                mutableState.update { it.copy(capturing = false) }
            }
        }
    }

    fun <T> importImages(images: List<T>, copy: suspend (T, File) -> Unit) {
        if (!state.value.canEdit || images.isEmpty()) return
        mutableState.update { it.copy(importing = true) }
        viewModelScope.launch {
            try {
                for (image in images) {
                    var finished = false
                    reportErrors { finished = addPage { file -> copy(image, file) } }
                    if (state.value.chooseIdSide) break
                    if (finished) {
                        reportErrors { reviewWhenReady() }
                        break
                    }
                }
            } finally {
                mutableState.update { it.copy(importing = false) }
            }
        }
    }

    fun addIdSide() {
        val current = state.value
        if (current.chooseIdSide && !current.closing && !current.capturing && !current.importing &&
            current.idSideIds.size < MAX_ID_SIDES
        ) {
            mutableState.update { it.copy(chooseIdSide = false) }
        }
    }

    fun finishId() {
        val current = state.value
        if (!current.chooseIdSide || current.closing || current.capturing || current.importing) return
        mutableState.update { it.copy(closing = true) }
        viewModelScope.launch {
            try {
                reportErrors { completeId() }
            } finally {
                mutableState.update { it.copy(closing = false) }
            }
        }
    }

    private suspend fun completeId() {
        check(processing.createIdComposite(state.value.idSideIds))
        reviewWhenReady()
        mutableState.update { it.copy(idSideIds = emptyList(), chooseIdSide = false) }
    }

    fun review() {
        val current = state.value
        if (current.canEdit && !current.idBack && current.pageIds.isNotEmpty()) prepareReview()
    }

    fun requestPageDeletion(pageId: String) {
        val current = state.value
        if (current.canEdit && !current.idBack && pageId in current.pageIds) {
            mutableState.update { it.copy(pageToDelete = pageId) }
        }
    }

    fun dismissPageDeletion() {
        mutableState.update { it.copy(pageToDelete = null) }
    }

    fun confirmPageDeletion() {
        val pageId = state.value.pageToDelete ?: return
        mutableState.update { it.copy(pageToDelete = null, closing = true) }
        viewModelScope.launch {
            try {
                reportErrors {
                    processing.awaitIdle(latestPageIds())
                    if (pageId in latestPageIds()) documents.deletePage(pageId)
                }
            } finally {
                mutableState.update { it.copy(closing = false) }
            }
        }
    }

    fun close() {
        val current = state.value
        if (current.chooseIdSide) {
            finishId()
            return
        }
        if (initializationFailed || (!current.closing && (current.pendingScan != null || !current.ready))) {
            eventChannel.trySend(CameraEvent.Close)
            return
        }
        if (!state.value.canEdit) return
        mutableState.update { it.copy(closing = true) }
        viewModelScope.launch {
            try {
                reportErrors {
                    val pages = latestPageIds()
                    if (pages.isEmpty()) discardSession(pages)
                    else mutableState.update { it.copy(pageIds = pages, confirmDiscard = true) }
                }
            } finally {
                mutableState.update { it.copy(closing = false) }
            }
        }
    }

    fun dismissDiscard() {
        mutableState.update { it.copy(confirmDiscard = false) }
    }

    fun keep() {
        if (!state.value.confirmDiscard) return
        dismissDiscard()
        prepareReview()
    }

    fun dismissDuplexMismatch() {
        mutableState.update { it.copy(confirmDuplexMismatch = false) }
    }

    fun keepDuplexOrder() {
        if (!state.value.confirmDuplexMismatch) return
        mutableState.update { it.copy(confirmDuplexMismatch = false, closing = true) }
        viewModelScope.launch {
            try {
                reportErrors { completeDuplexReview() }
            } finally {
                mutableState.update { it.copy(closing = false) }
            }
        }
    }

    fun discard() {
        if (!state.value.confirmDiscard) return
        mutableState.update { it.copy(closing = true, confirmDiscard = false) }
        viewModelScope.launch {
            try {
                reportErrors { discardSession(latestPageIds()) }
            } finally {
                mutableState.update { it.copy(closing = false) }
            }
        }
    }

    private suspend fun latestPageIds() =
        documents.observeDocument(session.draftDocumentId).first()?.pages.orEmpty().map { it.id }

    private suspend fun discardSession(pageIds: List<String>) {
        processing.awaitIdle(pageIds)
        sessions.discard(session.id)
        mutableState.update { it.copy(ready = false) }
        eventChannel.send(CameraEvent.Close)
    }

    private fun prepareReview() {
        if (duplex == DuplexProgress() && !waitForDerivedPages) {
            reviewDraft()
            return
        }
        mutableState.update { it.copy(closing = true) }
        viewModelScope.launch {
            try {
                reportErrors { reviewWhenReady() }
            } finally {
                mutableState.update { it.copy(closing = false) }
            }
        }
    }

    private suspend fun completeDuplexReview(ordered: List<String>? = null) {
        withContext(NonCancellable) {
            if (ordered != null) documents.reorderPages(session.draftDocumentId, ordered)
            updateDuplex(DuplexProgress())
        }
        reviewDraft()
    }

    private fun CameraState.withSideCounts() = copy(
        frontCount = pageIds.count { duplex.sides[it] == DuplexPhase.FRONTS },
        backCount = pageIds.count { duplex.sides[it] == DuplexPhase.BACKS },
    )

    private fun reviewDraft() {
        eventChannel.trySend(CameraEvent.Review(session.draftDocumentId))
    }

    private suspend fun reviewWhenReady() {
        if (waitForDerivedPages) processing.awaitIdle(latestPageIds())
        if (duplex == DuplexProgress()) {
            reviewDraft()
            return
        }

        val ids = latestPageIds()
        val draftIds = ids.toSet()
        val sides = duplex.sides.filterKeys { it in draftIds }
        val fronts = sides.filterValues { it == DuplexPhase.FRONTS }.keys.toList()
        val backs = sides.filterValues { it == DuplexPhase.BACKS }.keys.toList()
        val result = interleaveDuplex(fronts, backs)
        if (result.mismatch) {
            mutableState.update { it.copy(pageIds = ids, confirmDuplexMismatch = true).withSideCounts() }
        } else {
            val interleaved = result.pages.iterator()
            val ordered = ids.map { if (it in sides) interleaved.next() else it }
            completeDuplexReview(ordered.takeUnless { it == ids })
        }
    }

    private suspend fun addPage(hint: FloatArray? = null, write: suspend (File) -> Unit): Boolean {
        val current = state.value
        val mode = if (current.idBack) PageMode.ID_BACK else current.mode.pageMode
        withContext(NonCancellable) {
            val pageId = persistPage(mode, hint.takeIf { current.duplexPhase == null }, write)
            current.duplexPhase?.let { side ->
                updateDuplex(duplex.copy(sides = duplex.sides + (pageId to side)))
            }
            if (current.mode == ScanMode.ID_CARD) {
                mutableState.update {
                    it.copy(idSideIds = current.idSideIds + pageId, chooseIdSide = mode == PageMode.ID_BACK)
                }
            }
        }
        if (mode == PageMode.BOOK_L || current.mode == ScanMode.ID_CARD) waitForDerivedPages = true
        return current.singleMode && current.mode != ScanMode.ID_CARD
    }

    private suspend fun persistPage(mode: PageMode, hint: FloatArray?, write: suspend (File) -> Unit): String =
        withContext(io + NonCancellable) {
        // A started write can outlive the screen. Keep file cleanup and queue submission together.
        val temp = newTempFile()
        val page = try {
            write(temp)
            val size = imageSize(temp)
            require(size.width > 0 && size.height > 0)
            if (mode == PageMode.ID_FRONT || mode == PageMode.ID_BACK) {
                documents.addDraftPage(session.draftDocumentId, temp, size.width, size.height, mode)
            } else {
                documents.addDraftPage(session.draftDocumentId, temp, size.width, size.height)
            }
        } finally {
            temp.delete()
        }
        processing.enqueueNewPage(page.id, mode, hint = hint)
        page.id
    }

    private companion object {
        var lastSessionMode: Pair<String, ScanMode>? = null
        const val HINT_STABLE_MILLIS = 500L
    }

    private suspend fun reportErrors(block: suspend () -> Unit): Boolean = try {
        block()
        true
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        eventChannel.send(CameraEvent.Error)
        false
    }
}

private fun readImageSize(file: File): PixelSize {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    return PixelSize(bounds.outWidth, bounds.outHeight)
}

internal fun scanModeFor(key: String): ScanMode? = when (key) {
    "id_card" -> ScanMode.ID_CARD
    "book" -> ScanMode.BOOK
    "whiteboard" -> ScanMode.WHITEBOARD
    "document" -> ScanMode.DOCUMENT
    else -> null
}
