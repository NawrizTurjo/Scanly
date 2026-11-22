package dev.turjo.paperless.core.data

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

data class LibraryRow(
    val id: String,
    val title: String,
    val pageCount: Int,
    val coverId: String?,
    val createdAt: Long,
    val folderName: String?,
    val updatedAt: Long,
    val isFavorite: Boolean,
)

data class DocumentPagesRow(
    @Embedded val document: DocumentEntity,
    @Relation(parentColumn = "id", entityColumn = "documentId") val pages: List<PageEntity>,
)

@Dao
interface DocumentDao {
    @Query("SELECT id FROM pages")
    suspend fun pageIds(): List<String>

    @Query("SELECT id FROM documents WHERE deletedAt IS NULL")
    suspend fun liveDocumentIds(): List<String>

    @Query(
        """
        SELECT d.id, d.title, d.updatedAt, d.isFavorite, d.createdAt, f.name AS folderName,
            (SELECT COUNT(*) FROM pages p WHERE p.documentId = d.id) AS pageCount,
            COALESCE(
                (SELECT p.id FROM pages p WHERE p.id = d.coverPageId AND p.documentId = d.id),
                (SELECT p.id FROM pages p WHERE p.documentId = d.id ORDER BY p.`index` LIMIT 1)
            ) AS coverId
        FROM documents d LEFT JOIN folders f ON d.folderId = f.id
        WHERE d.isDraft = 0 AND d.deletedAt IS NULL
          AND (:folderId IS NULL OR d.folderId = :folderId)
          AND (:favouritesOnly = 0 OR d.isFavorite = 1)
        ORDER BY d.updatedAt DESC, d.id
        """,
    )
    fun observeLibrary(folderId: String?, favouritesOnly: Boolean): Flow<List<LibraryRow>>

    @Query("SELECT id, title, deletedAt FROM documents WHERE deletedAt IS NOT NULL ORDER BY deletedAt DESC, id")
    fun observeDeleted(): Flow<List<dev.turjo.paperless.core.model.DeletedDocument>>

    @Query("SELECT id FROM documents WHERE deletedAt < :cutoff")
    suspend fun expiredDocuments(cutoff: Long): List<String>

    @Transaction
    @Query("SELECT * FROM documents WHERE id = :id")
    fun observeDocument(id: String): Flow<DocumentPagesRow?>

    @Query("SELECT * FROM documents WHERE id = :id")
    suspend fun document(id: String): DocumentEntity?

    @Query("SELECT * FROM pages WHERE id = :id")
    suspend fun page(id: String): PageEntity?

    @Query("SELECT * FROM pages WHERE documentId = :id ORDER BY `index`")
    suspend fun pages(id: String): List<PageEntity>

    @Insert
    suspend fun insertDocument(document: DocumentEntity)

    @Insert
    suspend fun insertPage(page: PageEntity)

    @Update
    suspend fun updateDocument(document: DocumentEntity)

    @Update
    suspend fun updatePage(page: PageEntity)

    @Query("UPDATE pages SET `index` = `index` + 1 WHERE documentId = :id AND `index` >= :at")
    suspend fun shiftForInsert(id: String, at: Int)

    @Query("UPDATE pages SET `index` = `index` - 1 WHERE documentId = :id AND `index` > :at")
    suspend fun shiftAfterDelete(id: String, at: Int)

    @Query("UPDATE pages SET documentId = :documentId, `index` = :position WHERE id = :pageId")
    suspend fun setPagePosition(pageId: String, documentId: String, position: Int)

    @Query("DELETE FROM pages WHERE id = :id")
    suspend fun deletePage(id: String)

    @Query("DELETE FROM documents WHERE id = :id")
    suspend fun deleteDocument(id: String)
}

@Dao
interface ScanSessionDao {
    @Query("SELECT * FROM scan_sessions LIMIT 1")
    fun observeActive(): Flow<ScanSessionEntity?>

    @Query("SELECT * FROM scan_sessions LIMIT 1")
    suspend fun active(): ScanSessionEntity?

    @Query("SELECT * FROM scan_sessions WHERE id = :id")
    suspend fun session(id: String): ScanSessionEntity?

    @Insert
    suspend fun insert(session: ScanSessionEntity)

    @Query("DELETE FROM scan_sessions WHERE id = :id")
    suspend fun delete(id: String)
}
