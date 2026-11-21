package dev.turjo.paperless.core.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import dev.turjo.paperless.core.model.ReaderViewMode
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "external_pdfs", indices = [Index("uri", unique = true)])
internal data class ExternalPdfEntity(
    @PrimaryKey val id: String,
    val uri: String,
    val displayName: String,
    val lastOpenedAt: Long = 0,
    val pageCount: Int = 0,
    val lastPage: Int = 0,
    val persistedPermission: Boolean,
)

@Entity(tableName = "reader_tabs")
internal data class ReaderTabEntity(
    @PrimaryKey val id: String,
    val pdfKey: String,
    val ord: Int,
    val isActive: Boolean,
    val page: Int,
    val zoom: Float,
    val scrollOffsetPx: Int,
    val viewMode: ReaderViewMode,
    val nightMode: Boolean,
    val title: String,
)

internal data class RecentPdfRow(
    val pdfKey: String,
    val title: String,
    val lastOpenedAt: Long,
    val pageCount: Int,
    val lastPage: Int,
)

@Dao
internal interface ReaderDao {
    @Query("SELECT * FROM reader_tabs ORDER BY ord")
    fun observeTabs(): Flow<List<ReaderTabEntity>>

    @Query("SELECT id FROM reader_tabs WHERE isActive = 1 LIMIT 1")
    fun observeActiveTabId(): Flow<String?>

    @Query("DELETE FROM reader_tabs")
    suspend fun deleteTabs()

    @Insert
    suspend fun insertTabs(tabs: List<ReaderTabEntity>)

    @Query(
        """
        SELECT id AS pdfKey, displayName AS title, lastOpenedAt, pageCount, lastPage
        FROM external_pdfs WHERE lastOpenedAt > 0
        UNION ALL
        SELECT 'doc:' || d.id, d.title, d.lastOpenedAt, d.pageCount, d.lastPage
        FROM documents d LEFT JOIN folders f ON d.folderId = f.id
        WHERE d.lastOpenedAt > 0 AND d.deletedAt IS NULL AND d.isDraft = 0
          AND COALESCE(f.isPrivate, 0) = 0
        ORDER BY lastOpenedAt DESC, pdfKey
        LIMIT 20
        """,
    )
    fun observeRecent(): Flow<List<RecentPdfRow>>

    @Query("SELECT * FROM external_pdfs WHERE uri = :uri")
    suspend fun externalByUri(uri: String): ExternalPdfEntity?

    @Query("SELECT uri FROM external_pdfs WHERE id = :id")
    suspend fun externalUri(id: String): String?

    @Insert
    suspend fun insertExternal(pdf: ExternalPdfEntity)

    @Update
    suspend fun updateExternal(pdf: ExternalPdfEntity)

    @Query("UPDATE external_pdfs SET lastOpenedAt = :now, pageCount = :pageCount, lastPage = :lastPage WHERE id = :id")
    suspend fun markExternalOpened(id: String, now: Long, pageCount: Int, lastPage: Int)

    @Query("UPDATE documents SET lastOpenedAt = :now, pageCount = :pageCount, lastPage = :lastPage WHERE id = :id")
    suspend fun markDocumentOpened(id: String, now: Long, pageCount: Int, lastPage: Int)
}
