package dev.turjo.paperless.feature.camera

import android.graphics.BitmapFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.turjo.paperless.core.data.DocumentRepository
import dev.turjo.paperless.core.data.FileStore
import dev.turjo.paperless.core.data.PageProcessing
import dev.turjo.paperless.core.data.PixelSize
import dev.turjo.paperless.core.data.ScanSessionRepository
import dev.turjo.paperless.core.model.ScanSession
import java.io.File
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

internal data class CameraArgs(val targetDocumentId: String?, val insertAt: Int?, val singleMode: Boolean)

internal data class CameraState(
    val ready: Boolean = false,
    val singleMode: Boolean,
    val pageIds: List<String> = emptyList(),
    val capturing: Boolean = false,
    val importing: Boolean = false,
    val closing: Boolean = false,
    val confirmDiscard: Boolean = false,
) {
    val canEdit get() = ready && !capturing && !importing && !closing && !confirmDiscard
}

internal sealed interface CameraEvent {
    data class Review(val draftId: String) : CameraEvent
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
) : ViewModel() {
    @AssistedInject
    constructor(
        @Assisted args: CameraArgs,
        sessions: ScanSessionRepository,
        documents: DocumentRepository,
        processing: PageProcessing,
        files: FileStore,
    ) : this(
        args, sessions, documents, processing, { files.newTempFile(".jpg") },
        ::readImageSize, files::thumbFile, Dispatchers.IO,
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

    init {
        viewModelScope.launch {
            initializationFailed = !reportErrors {
                session = sessions.active() ?: sessions.start(args.targetDocumentId, args.insertAt)
                documents.observeDocument(session.draftDocumentId).collect { draft ->
                    val ids = draft?.pages.orEmpty().map { it.id }
                    mutableState.update { it.copy(ready = draft != null, pageIds = ids) }
                }
            }
        }
    }

    fun setSingleMode(single: Boolean) {
        if (state.value.canEdit) mutableState.update { it.copy(singleMode = single) }
    }

    fun capture(controller: CaptureController) {
        if (!state.value.canEdit) return
        mutableState.update { it.copy(capturing = true) }
        viewModelScope.launch {
            try {
                reportErrors {
                    addPage { file ->
                        controller.capture(file)
                        eventChannel.trySend(CameraEvent.Captured)
                    }
                    if (state.value.singleMode) reviewDraft()
                }
            } finally {
                mutableState.update { it.copy(capturing = false) }
            }
        }
    }

    fun <T> importImages(images: List<T>, copy: suspend (T, File) -> Unit) {
        if (!state.value.canEdit || images.isEmpty()) return
        val single = state.value.singleMode
        mutableState.update { it.copy(importing = true) }
        viewModelScope.launch {
            try {
                for (image in images) {
                    val added = reportErrors { addPage { file -> copy(image, file) } }
                    if (single && added) {
                        reviewDraft()
                        break
                    }
                }
            } finally {
                mutableState.update { it.copy(importing = false) }
            }
        }
    }

    fun review() {
        if (state.value.canEdit && state.value.pageIds.isNotEmpty()) reviewDraft()
    }

    fun close() {
        if (initializationFailed || (::session.isInitialized && !state.value.ready)) {
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
        reviewDraft()
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

    private fun reviewDraft() {
        eventChannel.trySend(CameraEvent.Review(session.draftDocumentId))
    }

    private suspend fun addPage(write: suspend (File) -> Unit) = withContext(io + NonCancellable) {
        // A started write can outlive the screen. Keep file cleanup and queue submission together.
        val temp = newTempFile()
        val page = try {
            write(temp)
            val size = imageSize(temp)
            require(size.width > 0 && size.height > 0)
            documents.addPage(session.draftDocumentId, temp, size.width, size.height)
        } finally {
            temp.delete()
        }
        processing.enqueueNewPage(page.id)
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
