package dev.turjo.paperless.core.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
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
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
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
            .addMigrations(MIGRATION_2_3)
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
