package dev.turjo.paperless.core.data

import androidx.room.Database
import androidx.room.Embedded
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.RoomDatabase
import dev.turjo.paperless.core.model.Document
import dev.turjo.paperless.core.model.Folder
import dev.turjo.paperless.core.model.InkRemoval
import dev.turjo.paperless.core.model.Page
import dev.turjo.paperless.core.model.PageFilter
import dev.turjo.paperless.core.model.PageMode
import dev.turjo.paperless.core.model.Quad
import dev.turjo.paperless.core.model.ScanSession

@Entity(tableName = "folders")
data class FolderEntity(
    @PrimaryKey val id: String,
    val name: String,
    val parentId: String?,
    val createdAt: Long,
    val isPrivate: Boolean,
)

@Entity(tableName = "documents", indices = [Index("folderId")])
data class DocumentEntity(
    @PrimaryKey val id: String,
    val title: String,
    val folderId: String?,
    val isFavorite: Boolean,
    val isDraft: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val coverPageId: String?,
    val deletedAt: Long?,
    @ColumnInfo(defaultValue = "0") val lastOpenedAt: Long = 0,
    @ColumnInfo(defaultValue = "0") val lastPage: Int = 0,
    @ColumnInfo(defaultValue = "0") val pageCount: Int = 0,
)

@Entity(
    tableName = "pages",
    foreignKeys = [ForeignKey(
        entity = DocumentEntity::class,
        parentColumns = ["id"],
        childColumns = ["documentId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("documentId")],
)
data class PageEntity(
    @PrimaryKey val id: String,
    val documentId: String,
    val index: Int,
    val mode: PageMode,
    val originalPath: String,
    val originalWidth: Int,
    val originalHeight: Int,
    @Embedded(prefix = "quad_") val quad: Quad?,
    val rotation: Int,
    val filter: PageFilter,
    val brightness: Float,
    val contrast: Float,
    val shadowFix: Boolean,
    val glareFix: Boolean,
    val processedPath: String?,
    val processedHash: String?,
    val glareWarning: Boolean,
    val createdAt: Long,
    @ColumnInfo(defaultValue = "1") val deskew: Boolean = true,
    val inkRemoval: InkRemoval? = null,
)

@Entity(
    tableName = "scan_sessions",
    foreignKeys = [ForeignKey(
        entity = DocumentEntity::class,
        parentColumns = ["id"],
        childColumns = ["draftDocumentId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("draftDocumentId")],
)
data class ScanSessionEntity(
    @PrimaryKey val id: String,
    val draftDocumentId: String,
    val startedAt: Long,
    val targetDocumentId: String?,
    val insertAt: Int?,
)

@Database(
    entities = [
        FolderEntity::class, DocumentEntity::class, PageEntity::class, ScanSessionEntity::class,
        ExternalPdfEntity::class, ReaderTabEntity::class,
        AnnotationEntity::class, PageTextEntity::class, IndexedPdfEntity::class,
        TagEntity::class, DocumentTagEntity::class,
    ],
    version = 5,
    exportSchema = true,
)
abstract class PaperlessDatabase : RoomDatabase() {
    internal abstract fun tagDao(): TagDao
    abstract fun documentDao(): DocumentDao
    abstract fun folderDao(): FolderDao
    abstract fun scanSessionDao(): ScanSessionDao
    internal abstract fun readerDao(): ReaderDao
    abstract fun annotationDao(): AnnotationDao
    abstract fun pageTextDao(): PageTextDao
}

internal fun FolderEntity.toModel() = Folder(id, name, parentId, createdAt, isPrivate)
internal fun Folder.toEntity() = FolderEntity(id, name, parentId, createdAt, isPrivate)

internal fun DocumentEntity.toModel() =
    Document(id, title, folderId, isFavorite, isDraft, createdAt, updatedAt, coverPageId, deletedAt)

internal fun Document.toEntity() =
    DocumentEntity(id, title, folderId, isFavorite, isDraft, createdAt, updatedAt, coverPageId, deletedAt)

internal fun PageEntity.toModel() = Page(
    id, documentId, index, mode, originalPath, originalWidth, originalHeight, quad, rotation, filter,
    brightness, contrast, shadowFix, glareFix, processedPath, processedHash, glareWarning, createdAt,
    deskew, inkRemoval,
)

internal fun Page.toEntity() = PageEntity(
    id, documentId, index, mode, originalPath, originalWidth, originalHeight,
    quad?.takeUnless { it == Quad.FULL }, rotation, filter, brightness, contrast, shadowFix, glareFix,
    processedPath, processedHash, glareWarning, createdAt, deskew, inkRemoval,
)

internal fun ScanSessionEntity.toModel() =
    ScanSession(id, draftDocumentId, startedAt, targetDocumentId, insertAt)

internal fun ScanSession.toEntity() =
    ScanSessionEntity(id, draftDocumentId, startedAt, targetDocumentId, insertAt)

/** v2: per-page auto-deskew (on by default, SC-16) and coloured-ink removal (SC-15). */
val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE pages ADD COLUMN deskew INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE pages ADD COLUMN inkRemoval TEXT")
    }
}
