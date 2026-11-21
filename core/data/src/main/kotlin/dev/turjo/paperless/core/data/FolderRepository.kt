package dev.turjo.paperless.core.data

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.Query
import androidx.room.withTransaction
import dev.turjo.paperless.core.model.FolderSummary
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

interface FolderRepository {
    fun observeFolders(): Flow<List<FolderSummary>>
    suspend fun create(name: String, parentId: String? = null): String
    suspend fun rename(id: String, name: String)
    suspend fun delete(id: String)
    suspend fun moveDocuments(documentIds: Set<String>, folderId: String?)
    suspend fun moveDocumentsToNewFolder(documentIds: Set<String>, name: String) {
        moveDocuments(documentIds, create(name))
    }
}

data class FolderRow(@Embedded val folder: FolderEntity, val documentCount: Int)

@Dao
interface FolderDao {
    @Query("""
        SELECT f.*, (SELECT COUNT(*) FROM documents d
            WHERE d.folderId = f.id AND d.isDraft = 0 AND d.deletedAt IS NULL) AS documentCount
        FROM folders f ORDER BY f.name COLLATE NOCASE, f.id
    """)
    fun observeFolders(): Flow<List<FolderRow>>

    @Query("SELECT * FROM folders WHERE id = :id")
    suspend fun folder(id: String): FolderEntity?

    @Insert
    suspend fun insert(folder: FolderEntity)

    @Query("UPDATE folders SET name = :name WHERE id = :id")
    suspend fun rename(id: String, name: String)

    @Query("UPDATE folders SET parentId = NULL WHERE parentId = :id")
    suspend fun promoteChildren(id: String)

    @Query("UPDATE documents SET folderId = NULL WHERE folderId = :id")
    suspend fun unfileDocuments(id: String)

    @Query("DELETE FROM folders WHERE id = :id")
    suspend fun delete(id: String)

    @Query("""
        UPDATE documents SET folderId = :folderId, updatedAt = :now
        WHERE id = :id AND isDraft = 0 AND deletedAt IS NULL AND folderId IS NOT :folderId
    """)
    suspend fun moveDocument(id: String, folderId: String?, now: Long)
}

class RoomFolderRepository @Inject constructor(
    private val database: PaperlessDatabase,
) : FolderRepository {
    private val dao = database.folderDao()

    override fun observeFolders() = dao.observeFolders().map { rows ->
        rows.map { FolderSummary(it.folder.toModel(), it.documentCount) }
    }

    override suspend fun create(name: String, parentId: String?): String = transaction {
        if (parentId != null) require(requireNotNull(dao.folder(parentId)).parentId == null)
        insertFolder(name, parentId)
    }

    private suspend fun insertFolder(name: String, parentId: String?): String {
        val id = UUID.randomUUID().toString()
        dao.insert(FolderEntity(id, validatedName(name), parentId, System.currentTimeMillis(), false))
        return id
    }

    override suspend fun rename(id: String, name: String) = transaction {
        dao.rename(id, validatedName(name))
    }

    override suspend fun delete(id: String) = transaction {
        dao.unfileDocuments(id)
        dao.promoteChildren(id)
        dao.delete(id)
    }

    override suspend fun moveDocuments(documentIds: Set<String>, folderId: String?) = transaction {
        if (folderId != null) requireNotNull(dao.folder(folderId))
        move(documentIds, folderId)
    }

    override suspend fun moveDocumentsToNewFolder(documentIds: Set<String>, name: String) = transaction {
        move(documentIds, insertFolder(name, null))
    }

    private suspend fun move(documentIds: Set<String>, folderId: String?) {
        val now = System.currentTimeMillis()
        documentIds.forEach { dao.moveDocument(it, folderId, now) }
    }

    private fun validatedName(name: String) = name.trim().also { require(it.isNotEmpty()) }

    private suspend fun <T> transaction(block: suspend () -> T): T =
        withContext(Dispatchers.IO) { database.withTransaction(block) }
}
