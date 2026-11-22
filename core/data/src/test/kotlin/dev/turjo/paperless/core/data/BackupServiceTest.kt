package dev.turjo.paperless.core.data

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.turjo.paperless.core.model.InkRemoval
import dev.turjo.paperless.core.model.PageFilter
import dev.turjo.paperless.core.model.PageMode
import dev.turjo.paperless.core.model.Quad
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BackupServiceTest {
    @get:Rule val temp = TemporaryFolder()
    private val app = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun restoresFoldersDocumentPagesAnnotationsAndTagsAndSkipsExistingIds() = runBlocking {
        val root = temp.newFolder("backup")
        val parent = FolderEntity("parent", "Courses", null, 123, false)
        val folder = FolderEntity("folder", "Notes / cards", parent.id, 234, true)
        val document = DocumentEntity("doc", "Card: 01", folder.id, true, false,
            1, 2, "page-1", null, lastOpenedAt = 123, lastPage = 1, pageCount = 2)
        val annotation = AnnotationEntity("a", "doc:doc", 1, "NOTE", "{}", 0, 1.5f, 345)
        val tag = TagEntity("t1", "Physics", 4, 567)
        val source = storage("source")
        val pages = List(2) { index ->
            val id = "page-$index"
            val files = FileStore(source)
            val original = files.originalFile(id).apply { parentFile!!.mkdirs(); writeText("orig $index") }
            val processed = files.processedFile(id).apply { writeText("proc $index") }
            PageEntity(id, document.id, index, PageMode.ID_FRONT, original.path, 250, 300,
                Quad(.1f, .2f, .8f, .2f, .8f, .9f, .1f, .9f), 90, PageFilter.DOCUMENT,
                .15f, .25f, true, true, processed.path, "hash-$index", true, 123, false, InkRemoval.BLUE)
        }
        val backup = database().withDatabase { db ->
            db.folderDao().insert(parent)
            db.folderDao().insert(folder)
            db.documentDao().insertDocument(document)
            for (page in pages) db.documentDao().insertPage(page)
            db.tagDao().insert(tag)
            db.tagDao().insertLinks(listOf(DocumentTagEntity(document.id, tag.id)))
            db.annotationDao().upsert(listOf(annotation))
            service(db, source).backup(FileBackupTree(root), setOf(folder.id)) {}
        }
        assertEquals(1, backup.documents)
        val backupDir = File(root, backup.location)
        assertTrue(File(backupDir, "Notes _ cards/Card_ 01/Card_ 01.pdf").isFile)
        val target = storage("target")
        database().withDatabase { db ->
            val service = service(db, target)
            val tree = FileBackupTree(backupDir)
            assertEquals(BackupResult("", 1, 0), service.restore(tree) {})
            assertEquals(setOf(parent, folder), db.folderDao().observeFolders().first().map { it.folder }.toSet())
            val restored = db.documentDao().pages(document.id)
            assertEquals(pages.size, restored.size)
            assertEquals(document.copy(coverPageId = restored[1].id), db.documentDao().document(document.id))
            for ((before, after) in pages.zip(restored)) {
                assertNotEquals(before.id, after.id)
                assertEquals(before.copy(id = after.id, originalPath = after.originalPath,
                    processedPath = after.processedPath), after)
                assertEquals(File(before.originalPath).readText(), File(after.originalPath).readText())
                assertEquals(File(before.processedPath!!).readText(), File(after.processedPath!!).readText())
            }
            assertEquals(listOf(annotation), db.annotationDao().all(annotation.pdfKey))
            assertEquals(listOf(tag), db.tagDao().observeDocumentTags(document.id).first())
            db.documentDao().updateDocument(document.copy(title = "Renamed", coverPageId = restored[1].id))
            assertEquals(BackupResult("", 0, 1), service.restore(tree) {})
            assertEquals("Renamed", db.documentDao().document(document.id)?.title)
        }
    }

    @Test
    fun restoresLegacyManifestWithoutTags() = runBlocking {
        val root = temp.newFolder("legacy")
        File(root, "library.json").writeText(
            """
            {"schemaVersion":1,"folders":[{"id":"old","name":"Legacy","createdAt":1,"isPrivate":false}],
             "documents":[{"id":"doc","title":"Legacy PDF","folderId":"old","isFavorite":false,
             "createdAt":1,"updatedAt":1,"lastOpenedAt":0,"lastPage":0,"pageCount":0,"folderDirectory":"Legacy","documentDirectory":"Legacy PDF",
             "pages":[],"annotations":[]}]}
            """.trimIndent(),
        )
        File(root, "Legacy/Legacy PDF").apply {
            resolve("orig").mkdirs()
            resolve("proc").mkdirs()
            fixture("text-vector.pdf").copyTo(resolve("Legacy PDF.pdf"))
        }
        database().withDatabase { db ->
            assertEquals(BackupResult("", 1, 0), service(db, storage("legacy-target")).restore(FileBackupTree(root)) {})
            assertEquals("Legacy PDF", db.documentDao().document("doc")?.title)
            assertTrue(db.tagDao().all().isEmpty())
            assertTrue(db.tagDao().links().isEmpty())
        }
    }

    @Test
    fun rejectsUnsupportedSchemaBeforeImporting() = runBlocking {
        val root = temp.newFolder("future")
        File(root, "library.json").writeText("""{"schemaVersion":99}""")
        database().withDatabase { db ->
            val error = runCatching { service(db, storage("target")).restore(FileBackupTree(root)) {} }.exceptionOrNull()
            assertEquals("Unsupported backup schema version: 99", error?.message)
            assertTrue(db.folderDao().observeFolders().first().isEmpty())
        }
    }

    private fun database() = Room.inMemoryDatabaseBuilder(app, PaperlessDatabase::class.java).build()

    private fun storage(name: String): Context {
        val directory = temp.newFolder(name)
        return object : ContextWrapper(app) {
            override fun getFilesDir() = File(directory, "files")
            override fun getCacheDir() = File(directory, "cache")
        }
    }

    private fun service(db: PaperlessDatabase, context: Context) = BackupService(db, context, FileStore(context)) { doc, file ->
        fixture("text-vector.pdf").copyTo(file, overwrite = true)
        doc.updatedAt
    }
}

internal fun fixture(name: String) = File(requireNotNull(System.getProperty("paperless.pdfFixtures")), name)

internal inline fun <T> PaperlessDatabase.withDatabase(block: (PaperlessDatabase) -> T): T =
    try {
        block(this)
    } finally {
        close()
    }

