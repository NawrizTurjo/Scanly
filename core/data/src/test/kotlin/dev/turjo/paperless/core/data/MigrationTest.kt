package dev.turjo.paperless.core.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Builds a real v1 database from the exported schema, then lets Room open it with the migrations.
 * Room validates the migrated schema against the current schema.
 * (MigrationTestHelper isn't used: under Robolectric on Windows its driver rejects the full database path.)
 */
@RunWith(RobolectricTestRunner::class)
class MigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun v1PagesGetDeskewOnAndNoInkRemoval() {
        val file = context.getDatabasePath("migration-test.db").apply { parentFile?.mkdirs(); delete() }
        createDatabase(file, 1)
        val db = Room.databaseBuilder(context, PaperlessDatabase::class.java, file.absolutePath)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
            .allowMainThreadQueries()
            .build()
        try {
            val page = runBlocking { db.documentDao().page("p1") }!!
            assertEquals(true, page.deskew)
            assertNull(page.inkRemoval)
        } finally {
            db.close()
        }
    }

    @Test
    fun v2DocumentsGetDefaultReadingProgress() = runBlocking {
        val file = context.getDatabasePath("migration-v2-test.db").apply { parentFile?.mkdirs(); delete() }
        createDatabase(file, 2)
        val db = Room.databaseBuilder(context, PaperlessDatabase::class.java, file.absolutePath)
            .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
            .build()
        try {
            val document = requireNotNull(db.documentDao().document("d1"))
            assertEquals(0L, document.lastOpenedAt)
            assertEquals(0, document.lastPage)
            assertEquals(0, document.pageCount)
            assertEquals("p1", db.documentDao().pages("d1").single().id)
            db.readerDao().insertExternal(ExternalPdfEntity("e1", "content://test/pdf", "PDF", persistedPermission = true))
            assertEquals("content://test/pdf", db.readerDao().externalUri("e1"))
        } finally {
            db.close()
        }
    }

    @Test
    fun v3GetsAnnotationsAndSearchableText() = runBlocking {
        val file = context.getDatabasePath("migration-v3-test.db").apply { parentFile?.mkdirs(); delete() }
        createDatabase(file, 3)
        val db = Room.databaseBuilder(context, PaperlessDatabase::class.java, file.absolutePath)
            .addMigrations(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
            .build()
        try {
            db.annotationDao().upsert(listOf(AnnotationEntity("a1", "k", 0, "INK", "[]", 0, 2f, 1)))
            assertEquals(1, db.annotationDao().all("k").size)
            db.pageTextDao().insert(listOf(PageTextEntity("k", 3, "the quick brown fox")))
            val hit = db.pageTextDao().search("brown").single()
            assertEquals(3, hit.pageIndex)
            assertEquals(true, hit.snippet.contains("[brown]"))
        } finally {
            db.close()
        }
    }

    @Test
    fun v4DocumentsCanBeTaggedAfterMigration() = runBlocking {
        val file = context.getDatabasePath("migration-v4-test.db").apply { parentFile?.mkdirs(); delete() }
        createDatabase(file, 4)
        Room.databaseBuilder(context, PaperlessDatabase::class.java, file.absolutePath)
            .addMigrations(MIGRATION_4_5, MIGRATION_5_6)
            .build()
            .withDatabase { db ->
                assertEquals("p1", db.documentDao().pages("d1").single().id)
                val tag = TagEntity("t1", "Physics", 0, 1)
                db.tagDao().insert(tag)
                db.tagDao().insertLinks(listOf(DocumentTagEntity("d1", tag.id)))
                assertEquals(listOf(tag), db.tagDao().observeDocumentTags("d1").first())
            }
    }

    @Test
    fun v5LinksLegacyCompositeToAdjacentSources() = runBlocking {
        val file = context.getDatabasePath("migration-v5-test.db").apply { parentFile?.mkdirs(); delete() }
        createDatabase(file, 5)
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { sql ->
            sql.execSQL("UPDATE pages SET mode = 'ID_FRONT' WHERE id = 'p1'")
            for ((id, index, mode) in listOf(Triple("back", 1, "ID_BACK"), Triple("id-composite-back", 2, "NORMAL"))) {
                sql.execSQL(
                    "INSERT INTO pages (id, documentId, `index`, mode, originalPath, originalWidth, originalHeight, " +
                        "rotation, filter, brightness, contrast, shadowFix, glareFix, glareWarning, createdAt) " +
                        "VALUES (?, 'd1', ?, ?, '/o.jpg', 10, 20, 0, 'ORIGINAL', 0, 0, 0, 0, 0, 1)",
                    arrayOf<Any>(id, index, mode),
                )
            }
        }
        Room.databaseBuilder(context, PaperlessDatabase::class.java, file.absolutePath)
            .addMigrations(MIGRATION_5_6)
            .build()
            .withDatabase { db ->
                assertNull(db.documentDao().page("p1")?.idFrontId)
                val composite = requireNotNull(db.documentDao().page("id-composite-back"))
                assertEquals("p1", composite.idFrontId)
                assertNull(composite.idLayout)
            }
    }

    private fun createDatabase(file: File, version: Int) {
        val schema = JSONObject(File("schemas/dev.turjo.paperless.core.data.PaperlessDatabase/$version.json").readText())
            .getJSONObject("database")
        SQLiteDatabase.openOrCreateDatabase(file, null).use { sql ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                sql.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices") ?: continue
                for (j in 0 until indices.length()) {
                    sql.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                }
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) sql.execSQL(setup.getString(i))
            sql.execSQL(
                "INSERT INTO documents (id, title, folderId, isFavorite, isDraft, createdAt, updatedAt, coverPageId, deletedAt) " +
                    "VALUES ('d1', 'Doc', NULL, 0, 0, 1, 1, NULL, NULL)",
            )
            sql.execSQL(
                "INSERT INTO pages (id, documentId, `index`, mode, originalPath, originalWidth, originalHeight, rotation, " +
                    "filter, brightness, contrast, shadowFix, glareFix, processedPath, processedHash, glareWarning, createdAt) " +
                    "VALUES ('p1', 'd1', 0, 'NORMAL', '/o.jpg', 10, 20, 0, 'DOCUMENT', 0, 0, 0, 0, NULL, NULL, 0, 1)",
            )
            sql.version = version
        }
    }
}
