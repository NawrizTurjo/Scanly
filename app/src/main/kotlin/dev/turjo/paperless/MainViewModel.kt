package dev.turjo.paperless

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import androidx.core.content.IntentCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.turjo.paperless.core.data.DocumentRepository
import dev.turjo.paperless.core.data.FileStore
import dev.turjo.paperless.core.data.PageProcessing
import dev.turjo.paperless.core.data.ReaderRepository
import dev.turjo.paperless.core.data.ScanSessionRepository
import dev.turjo.paperless.core.data.SettingsRepository
import dev.turjo.paperless.core.designsystem.R as DesignR
import dev.turjo.paperless.core.model.AppLock
import dev.turjo.paperless.core.model.UserSettings
import dev.turjo.paperless.core.model.PdfKey
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

internal data class EntryEvent(
    val route: Route? = null,
    val message: Int? = null,
    val pdfKey: PdfKey? = null,
    val pdfTitle: String? = null,
    val previousScanSaved: Boolean = false,
)

@HiltViewModel
class MainViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    repository: SettingsRepository,
    private val documents: DocumentRepository,
    private val reader: ReaderRepository,
    sessions: ScanSessionRepository,
    files: FileStore,
    processing: PageProcessing,
) : ViewModel() {
    private val storedSettings = MutableStateFlow<UserSettings?>(null)
    val settings = storedSettings.asStateFlow()
    private val lockState = MutableStateFlow(true)
    val locked = lockState.asStateFlow()
    private var backgroundedAtMs: Long? = null
    private var coldStart = true

    private val fileIntents = Channel<Intent>(Channel.UNLIMITED)
    private val preparingPdfState = MutableStateFlow(false)
    val preparingPdf = preparingPdfState.asStateFlow()
    private var documentExport: Job? = null
    private val entryEvents = Channel<EntryEvent>(Channel.UNLIMITED)
    internal val entries = entryEvents.receiveAsFlow()
    private val openedDocument = MutableStateFlow<Pair<String, Long>?>(null)

    init {
        viewModelScope.launch {
            repository.settings.collect { settings ->
                val previous = storedSettings.value
                if (previous == null) checkLock(settings)
                if (settings.appLock == AppLock.OFF) lockState.value = false
                storedSettings.value = settings
            }
        }
        viewModelScope.launch {
            for (intent in fileIntents) {
                locked.first { !it }
                val type = intent.type ?: withContext(Dispatchers.IO) {
                    try {
                        intent.resolveType(context.contentResolver)
                    } catch (_: RuntimeException) {
                        null
                    }
                }
                if (!acceptsFileIntent(intent.action, type)) continue
                if (type == PDF_MIME_TYPE) {
                    val uris = if (intent.action == Intent.ACTION_VIEW) emptyList() else sharedUris(intent)
                    pdfEntryEvents(intent.action, type, intent.data, uris, Uri::getScheme) { uri ->
                        val title = withContext(Dispatchers.IO) { pdfDisplayName(uri) }
                        reader.registerExternal(uri, title) to title
                    }.forEach { entryEvents.send(it) }
                } else {
                    val result = importSharedImages(
                        sharedUris(intent).filter { it.scheme == ContentResolver.SCHEME_CONTENT },
                        context.contentResolver, files, documents, sessions, processing,
                    )
                    entryEvents.send(EntryEvent(
                        route = result.draftId?.let(::ReviewRoute),
                        message = R.string.share_import_error.takeIf { result.hadErrors },
                        previousScanSaved = result.previousScanSaved,
                    ))
                }
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            val manager = context.getSystemService(ShortcutManager::class.java)
            val previousId = manager.dynamicShortcuts.firstOrNull { it.id == LAST_DOCUMENT_SHORTCUT }
                ?.intent?.getStringExtra(ROUTE_EXTRA)?.let { startBackStack(it).last() as? DocumentRoute }
                ?.documentId
            var lastPublishedId = previousId
            var lastOpened: Pair<String, Long>? = null
            var newest: Pair<String, Long>? = null
            combine(documents.observeLibrary(), openedDocument) { library, opened -> library to opened }
                .collect { (library, opened) ->
                    val head = library.firstOrNull()?.let { it.id to it.updatedAt }
                    val id = when {
                        opened != lastOpened && library.any { it.id == opened?.first } -> opened?.first
                        newest != null && head != newest -> head?.first
                        library.any { it.id == lastPublishedId } -> lastPublishedId
                        else -> head?.first
                    }
                    newest = head
                    lastOpened = opened
                    if (id != lastPublishedId) {
                        if (id == null) {
                            manager.removeDynamicShortcuts(listOf(LAST_DOCUMENT_SHORTCUT))
                            lastPublishedId = null
                        } else if (manager.addDynamicShortcuts(listOf(lastDocumentShortcut(id)))) {
                            lastPublishedId = id
                        }
                    }
                }
        }
    }

    internal fun receiveFileIntent(intent: Intent) {
        fileIntents.trySend(intent)
    }

    internal fun openPdf(key: PdfKey) {
        entryEvents.trySend(EntryEvent(pdfKey = key))
    }

    /** Content-search hit: store the page as the last-read page so the Reader resumes there, then open it. */
    internal fun openPdfAtPage(key: PdfKey, page: Int) {
        viewModelScope.launch {
            val known = reader.observeRecent().first().firstOrNull { it.pdfKey == key }
            reader.markOpened(key, known?.pageCount ?: 0, page)
            entryEvents.send(EntryEvent(pdfKey = key, pdfTitle = known?.title))
        }
    }

    /** A tool's output file (cache) opens as a Reader tab like any external PDF. */
    internal fun openFileInReader(file: java.io.File) {
        viewModelScope.launch {
            try {
                val key = reader.registerExternal(android.net.Uri.fromFile(file), file.name)
                entryEvents.send(EntryEvent(pdfKey = key, pdfTitle = file.nameWithoutExtension))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                entryEvents.send(EntryEvent(message = R.string.pdf_open_error))
            }
        }
    }

    internal fun openDocumentInReader(id: String) {
        if (documentExport?.isActive == true) return
        documentExport = viewModelScope.launch {
            val showProgress = launch {
                delay(300)
                preparingPdfState.value = true
            }
            try {
                val key = reader.pdfKeyForDocument(id)
                val title = documents.observeDocument(id).first()?.document?.title
                entryEvents.send(EntryEvent(pdfKey = key, pdfTitle = title))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                entryEvents.send(EntryEvent(message = R.string.pdf_open_error))
            } finally {
                showProgress.cancel()
                preparingPdfState.value = false
            }
        }
    }

    private fun pdfDisplayName(uri: Uri): String {
        val name = try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
        } catch (_: RuntimeException) {
            null
        }
        return name?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.pdf_default_title)
    }

    internal fun openRoute(value: String?) {
        startBackStack(value).getOrNull(1)?.let { entryEvents.trySend(EntryEvent(route = it)) }
    }

    internal fun documentOpened(id: String) {
        openedDocument.value = id to SystemClock.elapsedRealtimeNanos()
    }

    internal fun onStart() {
        storedSettings.value?.let(::checkLock)
    }

    internal fun onStop() {
        backgroundedAtMs = SystemClock.elapsedRealtime()
    }

    internal fun unlock() {
        lockState.value = false
        backgroundedAtMs = null
    }

    private fun checkLock(settings: UserSettings) {
        val lockOnReturn = shouldLock(
            settings.appLock,
            settings.lockAfterMinutes,
            backgroundedAtMs,
            SystemClock.elapsedRealtime(),
            coldStart,
        )
        if (lockOnReturn) lockState.value = true
        coldStart = false
        backgroundedAtMs = null
    }

    private fun lastDocumentShortcut(id: String): ShortcutInfo =
        ShortcutInfo.Builder(context, LAST_DOCUMENT_SHORTCUT)
            .setShortLabel(context.getString(R.string.open_last_document))
            .setIcon(Icon.createWithResource(context, DesignR.drawable.ic_library))
            .setIntent(entryIntent(context, documentEntry(id)))
            .build()

    private companion object {
        const val LAST_DOCUMENT_SHORTCUT = "last_document"
    }
}

private fun sharedUris(intent: Intent): List<Uri> {
    val streams = when (intent.action) {
        Intent.ACTION_SEND -> listOfNotNull(
            IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java),
        )
        Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(
            intent, Intent.EXTRA_STREAM, Uri::class.java,
        ).orEmpty().filterIsInstance<Uri>()
        else -> emptyList()
    }
    val clip = intent.clipData
    val uris = if (streams.isNotEmpty() || clip == null) streams
    else (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
    return uris
}
