package dev.turjo.paperless.core.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.turjo.paperless.core.model.Tag
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "tags", indices = [Index(value = ["name"], unique = true)])
data class TagEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(collate = ColumnInfo.NOCASE) val name: String,
    val colorIndex: Int,
    val createdAt: Long,
) {
    internal fun toModel() = Tag(id, name, colorIndex, createdAt)
}

@Entity(
    tableName = "document_tags",
    primaryKeys = ["documentId", "tagId"],
    foreignKeys = [
        ForeignKey(DocumentEntity::class, ["id"], ["documentId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(TagEntity::class, ["id"], ["tagId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("tagId")],
)
data class DocumentTagEntity(val documentId: String, val tagId: String)

internal data class TagRow(@Embedded val tag: TagEntity, val documentCount: Int)

@Dao
internal interface TagDao {
    @Query("""
        SELECT t.*, (SELECT COUNT(*) FROM document_tags dt JOIN documents d ON d.id = dt.documentId
            WHERE dt.tagId = t.id AND d.isDraft = 0 AND d.deletedAt IS NULL) AS documentCount
        FROM tags t ORDER BY t.name, t.id
    """)
    fun observeTags(): Flow<List<TagRow>>

    @Query("""
        SELECT t.* FROM tags t JOIN document_tags dt ON t.id = dt.tagId
        WHERE dt.documentId = :documentId ORDER BY t.name, t.id
    """)
    fun observeDocumentTags(documentId: String): Flow<List<TagEntity>>

    @Query("SELECT * FROM tags")
    suspend fun all(): List<TagEntity>

    @Query("SELECT * FROM document_tags")
    suspend fun links(): List<DocumentTagEntity>

    @Query("SELECT * FROM tags WHERE name = :name")
    suspend fun byName(name: String): TagEntity?

    @Query("SELECT * FROM tags WHERE id = :id")
    suspend fun byId(id: String): TagEntity?

    @Query("SELECT colorIndex FROM tags ORDER BY createdAt DESC, rowid DESC LIMIT 1")
    suspend fun lastColor(): Int?

    @Insert
    suspend fun insert(tag: TagEntity)

    @Query("UPDATE tags SET name = :name WHERE id = :id")
    suspend fun rename(id: String, name: String)

    @Query("DELETE FROM tags WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM document_tags WHERE documentId = :documentId")
    suspend fun clearLinks(documentId: String)

    @Insert
    suspend fun insertLinks(links: List<DocumentTagEntity>)
}

internal fun validatedTagName(name: String) = name.trim().also {
    require(it.length in 1..30) { "Tag names must be 1 to 30 characters" }
}

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE tags (
                id TEXT NOT NULL PRIMARY KEY, name TEXT COLLATE NOCASE NOT NULL,
                colorIndex INTEGER NOT NULL, createdAt INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE UNIQUE INDEX index_tags_name ON tags (name)")
        db.execSQL("""
            CREATE TABLE document_tags (
                documentId TEXT NOT NULL, tagId TEXT NOT NULL, PRIMARY KEY (documentId, tagId),
                FOREIGN KEY (documentId) REFERENCES documents (id) ON DELETE CASCADE,
                FOREIGN KEY (tagId) REFERENCES tags (id) ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX index_document_tags_tagId ON document_tags (tagId)")
    }
}
