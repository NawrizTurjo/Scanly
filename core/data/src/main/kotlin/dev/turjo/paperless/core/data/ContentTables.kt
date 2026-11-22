package dev.turjo.paperless.core.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Editable annotation layers (PT-7), stored in PDF page space; flattened into a new PDF only on export. */
@Entity(tableName = "annotations", indices = [Index("pdfKey")])
data class AnnotationEntity(
    @PrimaryKey val id: String,
    val pdfKey: String,
    val pageIndex: Int,
    val type: String,          // INK, HIGHLIGHT, UNDERLINE, STRIKE, NOTE
    val payloadJson: String,   // points / rects / note text, PDF points with a top-left origin
    val color: Int,
    val width: Float,
    val createdAt: Long,
)

/** Full-text index of PDFs that have a text layer (DM-10). Scans have no text and are never indexed. */
@Fts4
@Entity(tableName = "page_text")
data class PageTextEntity(val pdfKey: String, val pageIndex: Int, val text: String)

/** What has been indexed, so the indexer only re-reads PDFs that changed. */
@Entity(tableName = "indexed_pdfs")
data class IndexedPdfEntity(
    @PrimaryKey val pdfKey: String,
    val modifiedAt: Long,
    val pageCount: Int,
    val hasText: Boolean,
)

data class PageTextHit(val pdfKey: String, val pageIndex: Int, val snippet: String)

@Dao
interface AnnotationDao {
    @Query("SELECT * FROM annotations WHERE pdfKey = :pdfKey ORDER BY pageIndex, createdAt")
    fun observe(pdfKey: String): Flow<List<AnnotationEntity>>

    @Query("SELECT * FROM annotations WHERE pdfKey = :pdfKey ORDER BY pageIndex, createdAt")
    suspend fun all(pdfKey: String): List<AnnotationEntity>

    @Upsert
    suspend fun upsert(items: List<AnnotationEntity>)

    @Query("DELETE FROM annotations WHERE id IN (:ids)")
    suspend fun delete(ids: List<String>)

    @Query("DELETE FROM annotations WHERE pdfKey = :pdfKey")
    suspend fun deleteAll(pdfKey: String)
}

@Dao
interface PageTextDao {
    @Insert
    suspend fun insert(rows: List<PageTextEntity>)

    @Query("DELETE FROM page_text WHERE pdfKey = :pdfKey")
    suspend fun deleteFor(pdfKey: String)

    @Query(
        "SELECT pdfKey, pageIndex, snippet(page_text, '[', ']', '…', -1, 12) AS snippet " +
            "FROM page_text WHERE page_text MATCH :query LIMIT :limit",
    )
    suspend fun search(query: String, limit: Int = 100): List<PageTextHit>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun markIndexed(row: IndexedPdfEntity)

    @Query("SELECT * FROM indexed_pdfs WHERE pdfKey = :pdfKey")
    suspend fun indexed(pdfKey: String): IndexedPdfEntity?

    @Query("DELETE FROM indexed_pdfs WHERE pdfKey = :pdfKey")
    suspend fun forget(pdfKey: String)
}

/** v4: annotation layers (PT-7) and the full-text page index (DM-10). SQL copied from the exported 4.json. */
val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `annotations` (`id` TEXT NOT NULL, `pdfKey` TEXT NOT NULL, " +
                "`pageIndex` INTEGER NOT NULL, `type` TEXT NOT NULL, `payloadJson` TEXT NOT NULL, `color` INTEGER NOT NULL, " +
                "`width` REAL NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_annotations_pdfKey` ON `annotations` (`pdfKey`)")
        db.execSQL(
            "CREATE VIRTUAL TABLE IF NOT EXISTS `page_text` USING FTS4(`pdfKey` TEXT NOT NULL, " +
                "`pageIndex` INTEGER NOT NULL, `text` TEXT NOT NULL)",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `indexed_pdfs` (`pdfKey` TEXT NOT NULL, `modifiedAt` INTEGER NOT NULL, " +
                "`pageCount` INTEGER NOT NULL, `hasText` INTEGER NOT NULL, PRIMARY KEY(`pdfKey`))",
        )
    }
}
