package dev.turjo.paperless.core.data

import androidx.room.withTransaction
import dev.turjo.paperless.core.model.Document
import dev.turjo.paperless.core.model.ScanSession
import dev.turjo.paperless.core.model.UserSettings
import dev.turjo.paperless.core.model.formatAutoName
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

interface ScanSessionRepository {
    suspend fun start(targetDocumentId: String? = null, insertAt: Int? = null): ScanSession
    suspend fun startFresh(
        saveExisting: Boolean,
        targetDocumentId: String? = null,
        insertAt: Int? = null,
        expectedSessionId: String? = null,
    ): FreshScanSession = throw UnsupportedOperationException("Starting a fresh scan is unavailable")
    fun observeActive(): Flow<ScanSession?>
    suspend fun active(): ScanSession?
    suspend fun finish(sessionId: String)
    suspend fun discard(sessionId: String)
}

data class FreshScanSession(val session: ScanSession, val savedTitle: String?)

class RoomScanSessionRepository internal constructor(
    private val database: PaperlessDatabase,
    private val documents: DocumentDao,
    private val sessions: ScanSessionDao,
    private val files: FileStore,
    private val documentRepository: DocumentRepository,
    private val settings: Flow<UserSettings>,
) : ScanSessionRepository {
    @Inject constructor(
        database: PaperlessDatabase,
        documents: DocumentDao,
        sessions: ScanSessionDao,
        files: FileStore,
        documentRepository: DocumentRepository,
        settings: SettingsRepository,
    ) : this(database, documents, sessions, files, documentRepository, settings.settings)

    override suspend fun start(targetDocumentId: String?, insertAt: Int?): ScanSession =
        withContext(Dispatchers.IO) {
            database.withTransaction {
                check(sessions.active() == null) { "A scan session is already active" }
                createSession(targetDocumentId, insertAt)
            }
        }

    override suspend fun startFresh(
        saveExisting: Boolean,
        targetDocumentId: String?,
        insertAt: Int?,
        expectedSessionId: String?,
    ): FreshScanSession = withContext(Dispatchers.IO) {
        val pattern = if (saveExisting) settings.first().autoNamePattern else null
        files.pageCreationLock.withLock {
            withContext(NonCancellable) {
                var oldSessionId: String? = null
                var discardedPages = emptyList<PageEntity>()
                val result = database.withTransaction {
                    val current = sessions.active()
                    check(expectedSessionId == null || current?.id == expectedSessionId) {
                        "The active scan session changed"
                    }
                    oldSessionId = current?.id
                    var savedTitle: String? = null
                    if (current != null) {
                        val draft = documents.document(current.draftDocumentId)
                        if (draft?.isDraft == true && draft.deletedAt == null) {
                            val pages = documents.pages(draft.id)
                            if (pages.isEmpty()) {
                                val reused = current.copy(targetDocumentId = targetDocumentId, insertAt = insertAt)
                                if (reused != current) sessions.update(reused)
                                return@withTransaction FreshScanSession(reused.toModel(), null)
                            }
                            if (pattern != null) {
                                val startedAt = Instant.ofEpochMilli(current.startedAt).atZone(ZoneId.systemDefault())
                                savedTitle = formatAutoName(pattern, startedAt.toLocalDateTime(), 1)
                                    .trim().ifEmpty { defaultTitle(current.startedAt) }
                                documentRepository.commitDraft(draft.id, savedTitle)
                            } else {
                                documents.deleteDocument(draft.id)
                                discardedPages = pages
                            }
                        }
                        sessions.delete(current.id)
                    }
                    FreshScanSession(createSession(targetDocumentId, insertAt), savedTitle)
                }
                oldSessionId?.let { files.duplexFile(it).delete() }
                discardedPages.forEach {
                    try {
                        files.deletePageFiles(it.id)
                    } catch (_: IOException) {
                        // The orphan sweep retries cleanup after the session switch has committed.
                    }
                }
                result
            }
        }
    }

    private suspend fun createSession(targetDocumentId: String?, insertAt: Int?): ScanSession {
        val now = System.currentTimeMillis()
        val draft = Document(
            UUID.randomUUID().toString(), defaultTitle(now), null, false, true, now, now, null, null,
        )
        val session = ScanSession(UUID.randomUUID().toString(), draft.id, now, targetDocumentId, insertAt)
        documents.insertDocument(draft.toEntity())
        sessions.insert(session.toEntity())
        return session
    }

    override fun observeActive() = sessions.observeActive().map { it?.toModel() }.flowOn(Dispatchers.IO)

    override suspend fun active(): ScanSession? = withContext(Dispatchers.IO) { sessions.active()?.toModel() }

    override suspend fun finish(sessionId: String) {
        withContext(Dispatchers.IO + NonCancellable) {
            sessions.delete(sessionId)
            files.duplexFile(sessionId).delete()
        }
    }

    override suspend fun discard(sessionId: String) = withContext(Dispatchers.IO) {
        withContext(NonCancellable) {
            val pages = database.withTransaction {
                val session = sessions.session(sessionId) ?: return@withTransaction emptyList()
                val draft = documents.document(session.draftDocumentId)
                if (draft?.isDraft != true) {
                    sessions.delete(sessionId)
                    return@withTransaction emptyList()
                }
                documents.pages(draft.id).also { documents.deleteDocument(draft.id) }
            }
            files.duplexFile(sessionId).delete()
            pages.forEach { files.deletePageFiles(it.id) }
        }
    }
}
