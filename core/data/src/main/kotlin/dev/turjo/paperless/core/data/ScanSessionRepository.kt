package dev.turjo.paperless.core.data

import androidx.room.withTransaction
import dev.turjo.paperless.core.model.Document
import dev.turjo.paperless.core.model.ScanSession
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

interface ScanSessionRepository {
    suspend fun start(targetDocumentId: String? = null, insertAt: Int? = null): ScanSession
    fun observeActive(): Flow<ScanSession?>
    suspend fun active(): ScanSession?
    suspend fun finish(sessionId: String)
    suspend fun discard(sessionId: String)
}

class RoomScanSessionRepository @Inject constructor(
    private val database: PaperlessDatabase,
    private val documents: DocumentDao,
    private val sessions: ScanSessionDao,
    private val files: FileStore,
) : ScanSessionRepository {
    override suspend fun start(targetDocumentId: String?, insertAt: Int?): ScanSession =
        withContext(Dispatchers.IO) {
            database.withTransaction {
                check(sessions.active() == null) { "A scan session is already active" }
                val now = System.currentTimeMillis()
                val draft = Document(
                    UUID.randomUUID().toString(), defaultTitle(now), null, false, true, now, now, null, null,
                )
                val session = ScanSession(UUID.randomUUID().toString(), draft.id, now, targetDocumentId, insertAt)
                documents.insertDocument(draft.toEntity())
                sessions.insert(session.toEntity())
                session
            }
        }

    override fun observeActive() = sessions.observeActive().map { it?.toModel() }.flowOn(Dispatchers.IO)

    override suspend fun active(): ScanSession? = withContext(Dispatchers.IO) { sessions.active()?.toModel() }

    override suspend fun finish(sessionId: String) {
        withContext(Dispatchers.IO) { sessions.delete(sessionId) }
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
            pages.forEach { files.deletePageFiles(it.id) }
        }
    }
}
