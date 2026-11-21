package dev.turjo.paperless.core.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE documents ADD COLUMN lastOpenedAt INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE documents ADD COLUMN lastPage INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE documents ADD COLUMN pageCount INTEGER NOT NULL DEFAULT 0")
        db.execSQL(
            """
            CREATE TABLE external_pdfs (
                id TEXT NOT NULL PRIMARY KEY, uri TEXT NOT NULL, displayName TEXT NOT NULL,
                lastOpenedAt INTEGER NOT NULL, pageCount INTEGER NOT NULL, lastPage INTEGER NOT NULL,
                persistedPermission INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE UNIQUE INDEX index_external_pdfs_uri ON external_pdfs (uri)")
        db.execSQL(
            """
            CREATE TABLE reader_tabs (
                id TEXT NOT NULL PRIMARY KEY, pdfKey TEXT NOT NULL, ord INTEGER NOT NULL, isActive INTEGER NOT NULL,
                page INTEGER NOT NULL, zoom REAL NOT NULL, scrollOffsetPx INTEGER NOT NULL, viewMode TEXT NOT NULL,
                nightMode INTEGER NOT NULL, title TEXT NOT NULL
            )
            """.trimIndent(),
        )
    }
}
