package dev.turjo.paperless.core.data

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

internal interface BackupTree {
    fun child(name: String): BackupTree?
    fun directory(name: String): BackupTree
    fun read(): InputStream
    fun write(name: String, mime: String, block: (OutputStream) -> Unit)
}

internal class SafBackupTree private constructor(
    private val resolver: ContentResolver,
    private val tree: Uri,
    private val id: String,
) : BackupTree {
    constructor(resolver: ContentResolver, tree: Uri) :
        this(resolver, tree, DocumentsContract.getTreeDocumentId(tree))

    private val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)

    override fun child(name: String): BackupTree? {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id)
        return resolver.query(children, arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME),
            null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getString(1) == name) return@use SafBackupTree(resolver, tree, cursor.getString(0))
            }
            null
        }
    }

    private fun create(name: String, mime: String): Uri {
        check(child(name) == null) { "Backup entry already exists: $name" }
        return DocumentsContract.createDocument(resolver, uri, mime, name)
            ?: throw IOException("Cannot create $name")
    }

    override fun directory(name: String): BackupTree {
        val directory = create(name, Document.MIME_TYPE_DIR)
        return SafBackupTree(resolver, tree, DocumentsContract.getDocumentId(directory))
    }

    override fun read(): InputStream = resolver.openInputStream(uri) ?: throw IOException("Cannot read $uri")

    override fun write(name: String, mime: String, block: (OutputStream) -> Unit) {
        val file = create(name, mime)
        (resolver.openOutputStream(file) ?: throw IOException("Cannot write $name")).use(block)
    }
}

internal class FileBackupTree(private val file: File) : BackupTree {
    override fun child(name: String): BackupTree? =
        File(file, name).takeIf(File::exists)?.let(::FileBackupTree)

    override fun directory(name: String): BackupTree {
        val directory = File(file, name)
        check(directory.mkdir()) { "Cannot create $directory" }
        return FileBackupTree(directory)
    }

    override fun read() = file.inputStream()
    override fun write(name: String, mime: String, block: (OutputStream) -> Unit) {
        val output = File(file, name)
        check(output.createNewFile()) { "Backup entry already exists: $name" }
        output.outputStream().use(block)
    }
}

